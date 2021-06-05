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

import java.net.SocketAddress;
import java.net.URL;

/**
 * A URL as a socket address.
 */
public class UrlSocketAddress extends SocketAddress {

	private static final long serialVersionUID = 1L;

	private final URL url;

	public UrlSocketAddress(URL url) {
		this.url = url;
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof UrlSocketAddress)) return false;
		UrlSocketAddress other = (UrlSocketAddress)obj;
		return url.toExternalForm().equals(other.url.toExternalForm());
	}

	@Override
	public int hashCode() {
		return url.toExternalForm().hashCode();
	}

	@Override
	public String toString() {
		return url.toExternalForm();
	}

	public URL getUrl() {
		return url;
	}
}
