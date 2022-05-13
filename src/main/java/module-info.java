/*
 * ao-messaging-http - Asynchronous bidirectional messaging over HTTP.
 * Copyright (C) 2021, 2022  AO Industries, Inc.
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
 * along with ao-messaging-http.  If not, see <https://www.gnu.org/licenses/>.
 */
module com.aoapps.messaging.http {
  exports com.aoapps.messaging.http;
  // Direct
  requires com.aoapps.concurrent; // <groupId>com.aoapps</groupId><artifactId>ao-concurrent</artifactId>
  requires com.aoapps.lang; // <groupId>com.aoapps</groupId><artifactId>ao-lang</artifactId>
  requires com.aoapps.messaging.api; // <groupId>com.aoapps</groupId><artifactId>ao-messaging-api</artifactId>
  requires com.aoapps.messaging.base; // <groupId>com.aoapps</groupId><artifactId>ao-messaging-base</artifactId>
  requires com.aoapps.security; // <groupId>com.aoapps</groupId><artifactId>ao-security</artifactId>
  requires com.aoapps.tempfiles; // <groupId>com.aoapps</groupId><artifactId>ao-tempfiles</artifactId>
  // Java SE
  requires java.logging;
  requires java.xml;
}
