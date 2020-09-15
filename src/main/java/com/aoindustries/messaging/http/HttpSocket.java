/*
 * ao-messaging-http - Asynchronous bidirectional messaging over HTTP.
 * Copyright (C) 2014, 2015, 2016, 2017, 2018, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-messaging-http.
 *
 * ao-messaging-http is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-messaging-http is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-messaging-http.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.messaging.http;

import com.aoindustries.concurrent.Callback;
import com.aoindustries.concurrent.Executor;
import com.aoindustries.concurrent.Executors;
import com.aoindustries.io.AoByteArrayOutputStream;
import com.aoindustries.lang.Throwables;
import com.aoindustries.messaging.Message;
import com.aoindustries.messaging.MessageType;
import com.aoindustries.messaging.Socket;
import com.aoindustries.messaging.base.AbstractSocket;
import com.aoindustries.security.Identifier;
import com.aoindustries.tempfiles.TempFileContext;
import com.aoindustries.util.AtomicSequence;
import com.aoindustries.util.Sequence;
import com.aoindustries.xml.XmlUtils;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/**
 * One established connection over HTTP.
 */
public class HttpSocket extends AbstractSocket {

	private static final Logger logger = Logger.getLogger(HttpSocket.class.getName());

	public static final String PROTOCOL = "http";

	public static final Charset ENCODING = StandardCharsets.UTF_8;

	private static final int CONNECT_TIMEOUT = 15 * 1000;

	/** Server should normally respond within 60 seconds even if no data coming back. */
	public static final int READ_TIMEOUT = 2 * 60 * 1000;

	private final Map<Long,Message> inQueue = new HashMap<>();
	private long inSeq = 1; // Synchronized on inQueue

	private final Object lock = new Object();
	private Queue<Message> outQueue;

	private final Sequence outSeq = new AtomicSequence();

	private final Executors executors = new Executors();

	private final HttpSocketContext socketContext;
	private final URL endpoint;

	/** The HttpURLConnection that is currently waiting for return traffic */
	private HttpURLConnection receiveConn;

	public HttpSocket(
		HttpSocketContext socketContext,
		Identifier id,
		long connectTime,
		URL endpoint
	) {
		super(
			socketContext,
			id,
			connectTime,
			new UrlSocketAddress(endpoint)
		);
		this.socketContext = socketContext;
		this.endpoint = endpoint;
	}

	@Override
	public void close() throws IOException {
		try {
			super.close();
		} finally {
			logger.log(Level.FINEST, "Notifying all on lock");
			synchronized(lock) {
				lock.notifyAll();
			}
			logger.log(Level.FINEST, "Notifying all on lock completed");
			logger.log(Level.FINER, "Calling executor.dispose()");
			executors.dispose();
			logger.log(Level.FINER, "executor.dispose() finished");
		}
	}

	@Override
	public String getProtocol() {
		return PROTOCOL;
	}

	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "ThrowableResultIgnored", "AssignmentToCatchBlockParameter"})
	protected void startImpl(
		Callback<? super Socket> onStart,
		Callback<? super Throwable> onError
	) throws IllegalStateException {
		executors.getUnbounded().submit(() -> {
			try {
				if(isClosed()) {
					SocketException e = new SocketException("Socket is closed");
					if(onError != null) {
						logger.log(Level.FINE, "Calling onError", e);
						try {
							onError.call(e);
						} catch(ThreadDeath td) {
							throw td;
						} catch(Throwable t) {
							logger.log(Level.SEVERE, null, t);
						}
					} else {
						logger.log(Level.FINE, "No onError", e);
					}
				} else {
					// Handle incoming messages in a Thread, can try nio later
					final Executor unbounded = executors.getUnbounded();
					unbounded.submit(() -> {
						try {
							TempFileContext tempFileContext;
							try {
								tempFileContext = new TempFileContext();
							} catch(ThreadDeath td) {
								throw td;
							} catch(Throwable t) {
								logger.log(Level.WARNING, null, t);
								tempFileContext = null;
							}
							try {
								while(!isClosed()) {
									HttpURLConnection _receiveConn = null;
									synchronized(lock) {
										// Wait until a connection is ready
										while(_receiveConn==null) {
											if(isClosed()) return;
											_receiveConn = HttpSocket.this.receiveConn;
											if(_receiveConn==null) {
												// No receive connection - kick-out an empty set of messages
												Collection<? extends Message> kicker = Collections.emptyList();
												sendMessagesImpl(kicker);
												lock.wait();
											}
										}
									}
									try {
										// Get response
										int responseCode = _receiveConn.getResponseCode();
										logger.log(Level.FINEST, "receive: Got response: {0}", responseCode);
										if(responseCode != 200) throw new IOException("Unexpect response code: " + responseCode);
										DocumentBuilder builder = socketContext.builderFactory.newDocumentBuilder();
										Element document = builder.parse(_receiveConn.getInputStream()).getDocumentElement();
										if(!"messages".equals(document.getNodeName())) throw new IOException("Unexpected root node name: " + document.getNodeName());
										// Add all messages to the inQueue by sequence to handle out-of-order messages
										List<Message> messages;
										synchronized(inQueue) {
											for(Element messageElem : XmlUtils.iterableChildElementsByTagName(document, "message")) {
												// Get the sequence
												Long seq = Long.parseLong(messageElem.getAttribute("seq"));
												// Get the type
												MessageType type = MessageType.getFromTypeChar(messageElem.getAttribute("type").charAt(0));
												// Get the message string
												Node firstChild = messageElem.getFirstChild();
												String encodedMessage;
												if(firstChild == null) {
													encodedMessage = "";
												} else {
													if(!(firstChild instanceof Text)) throw new IllegalArgumentException("Child of message is not a Text node");
													encodedMessage = ((Text)firstChild).getTextContent();
												}
												// Decode and add
												if(inQueue.put(seq, type.decode(encodedMessage, tempFileContext)) != null) {
													throw new IOException("Duplicate incoming sequence: " + seq);
												}
											}
											// Gather as many messages that have been delivered in-order
											messages = new ArrayList<>(inQueue.size());
											while(true) {
												Message message = inQueue.remove(inSeq);
												if(message != null) {
													messages.add(message);
													inSeq++;
												} else {
													// Break in the sequence
													break;
												}
											}
										}
										if(!messages.isEmpty()) {
											final Future<?> future = callOnMessages(Collections.unmodifiableList(messages));
											if(tempFileContext != null && tempFileContext.getSize() != 0) {
												// Close temp file context, thus deleting temp files, once all messages have been handled
												final TempFileContext closeMeNow = tempFileContext;
												unbounded.submit(() -> {
													try {
														try {
															// Wait until all messages handled
															future.get();
														} finally {
															try {
																// Delete temp files
																closeMeNow.close();
															} catch(ThreadDeath td) {
																throw td;
															} catch(Throwable t) {
																logger.log(Level.SEVERE, null, t);
															}
														}
													} catch(InterruptedException e) {
														logger.log(Level.FINE, null, e);
														Thread.currentThread().interrupt();
													} catch(ThreadDeath td) {
														throw td;
													} catch(Throwable t) {
														logger.log(Level.SEVERE, null, t);
													}
												});
												try {
													tempFileContext = new TempFileContext();
												} catch(ThreadDeath td) {
													throw td;
												} catch(Throwable t) {
													logger.log(Level.WARNING, null, t);
													tempFileContext = null;
												}
											}
										}
									} finally {
										synchronized(lock) {
											assert _receiveConn == HttpSocket.this.receiveConn;
											HttpSocket.this.receiveConn = null;
											lock.notify();
										}
									}
								}
							} finally {
								if(tempFileContext != null) {
									try {
										tempFileContext.close();
									} catch(ThreadDeath td) {
										throw td;
									} catch(Throwable t) {
										logger.log(Level.WARNING, null, t);
									}
								}
							}
						} catch(ThreadDeath td) {
							try {
								if(!isClosed()) callOnError(td);
							} catch(Throwable t) {
								Throwable t2 = Throwables.addSuppressed(td, t);
								assert t2 == td;
							}
							throw td;
						} catch(Throwable t) {
							if(!isClosed()) callOnError(t);
						} finally {
							try {
								close();
							} catch(ThreadDeath td) {
								throw td;
							} catch(Throwable t) {
								logger.log(Level.SEVERE, null, t);
							}
						}
					});
				}
				if(onStart != null) {
					logger.log(Level.FINE, "Calling onStart: {0}", HttpSocket.this);
					try {
						onStart.call(HttpSocket.this);
					} catch(ThreadDeath td) {
						throw td;
					} catch(Throwable t) {
						logger.log(Level.SEVERE, null, t);
					}
				} else {
					logger.log(Level.FINE, "No onStart: {0}", HttpSocket.this);
				}
			} catch(Throwable t0) {
				if(onError != null) {
					logger.log(Level.FINE, "Calling onError", t0);
					try {
						onError.call(t0);
					} catch(ThreadDeath td) {
						t0 = Throwables.addSuppressed(td, t0);
						assert t0 == td;
					} catch(Throwable t) {
						logger.log(Level.SEVERE, null, t);
					}
				} else {
					logger.log(Level.FINE, "No onError", t0);
				}
				if(t0 instanceof ThreadDeath) throw (ThreadDeath)t0;
			}
		});
	}

	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected void sendMessagesImpl(Collection<? extends Message> messages) {
		if(logger.isLoggable(Level.FINEST)) {
			int size = messages.size();
			logger.log(Level.FINEST, "Enqueuing {0} {1}", new Object[]{size, (size == 1) ? "message" : "messages"});
		}
		synchronized(lock) {
			// Enqueue asynchronous write
			boolean isFirst;
			if(outQueue == null) {
				outQueue = new LinkedList<>();
				isFirst = true;
			} else {
				isFirst = false;
			}
			outQueue.addAll(messages);
			if(isFirst) {
				logger.log(Level.FINEST, "Submitting runnable");
				// When the queue is first created, we submit the queue runner to the executor for queue processing
				// There is only one executor per queue, and on queue per socket
				executors.getUnbounded().submit(() -> {
					try {
						final List<Message> msgs = new ArrayList<>();
						while(!isClosed()) {
							// Get all of the messages until the queue is empty
							synchronized(lock) {
								if(outQueue.isEmpty() && receiveConn!=null) {
									logger.log(Level.FINEST, "run: Queue empty and receiveConn present, returning");
									// Remove the empty queue so a new executor will be submitted on next event
									outQueue = null;
									break;
								} else {
									msgs.addAll(outQueue);
									outQueue.clear();
								}
							}
							// Write the messages without holding the queue lock
							final int size = msgs.size();
							if(logger.isLoggable(Level.FINEST)) {
								logger.log(Level.FINEST, "run: Writing {0} {1}", new Object[]{size, (size == 1) ? "message" : "messages"});
							}
							// Build request bytes
							AoByteArrayOutputStream bout = new AoByteArrayOutputStream();
							try {
								try (DataOutputStream out = new DataOutputStream(bout)) {
									out.writeBytes("action=messages&id=");
									out.writeBytes(getId().toString());
									logger.log(Level.FINEST, "run: id = {0}", getId());
									out.writeBytes("&l=");
									out.writeBytes(Integer.toString(size));
									for(int i=0; i<size; i++) {
										String iString = Integer.toString(i);
										Message message = msgs.get(i);
										// Sequence
										out.writeBytes("&s");
										out.writeBytes(iString);
										out.write('=');
										out.writeBytes(Long.toString(outSeq.getNextSequenceValue()));
										// Type
										out.writeBytes("&t");
										out.writeBytes(iString);
										out.write('=');
										out.write(message.getMessageType().getTypeChar());
										// Message
										out.writeBytes("&m");
										out.writeBytes(iString);
										out.write('=');
										out.writeBytes(URLEncoder.encode(message.encodeAsString(), ENCODING.name()));
									}
								}
							} finally {
								bout.close();
							}
							HttpURLConnection conn = (HttpURLConnection)endpoint.openConnection();
							conn.setAllowUserInteraction(false);
							conn.setConnectTimeout(CONNECT_TIMEOUT);
							conn.setDoOutput(true);
							conn.setFixedLengthStreamingMode(bout.size());
							conn.setInstanceFollowRedirects(false);
							conn.setReadTimeout(READ_TIMEOUT);
							conn.setRequestMethod("POST");
							conn.setUseCaches(false);
							// Write request
							OutputStream out = conn.getOutputStream();
							try {
								out.write(bout.getInternalByteArray(), 0, bout.size());
								out.flush();
							} finally {
								out.close();
							}
							// Use this connection as the new receive connection
							synchronized(lock) {
								// Wait until receive connection available
								while(receiveConn!=null) {
									if(isClosed()) return;
									lock.wait();
								}
								receiveConn = conn;
								lock.notify();
							}
							msgs.clear();
						}
					} catch(Throwable t) {
						if(!isClosed()) {
							try {
								callOnError(t);
							} finally {
								try {
									close();
								} catch(ThreadDeath td) {
									throw td;
								} catch(Throwable t2) {
									logger.log(Level.SEVERE, null, t2);
								}
							}
						}
						if(t instanceof ThreadDeath) throw (ThreadDeath)t;
					}
				});
			}
		}
	}
}
