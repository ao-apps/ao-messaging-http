/*
 * ao-messaging-http - Asynchronous bidirectional messaging over HTTP.
 * Copyright (C) 2014, 2015, 2016, 2021  AO Industries, Inc.
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
package com.aoapps.messaging.http;

import com.aoapps.messaging.base.AbstractSocketContext;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Bi-directional messaging over HTTP.
 */
public abstract class HttpSocketContext extends AbstractSocketContext<HttpSocket> {

	protected final DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();

	public HttpSocketContext() {
	}
}
