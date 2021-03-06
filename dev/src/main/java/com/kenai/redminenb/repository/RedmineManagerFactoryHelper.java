/*
 * Copyright 2015 Matthias Bläsing.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kenai.redminenb.repository;

import com.taskadapter.redmineapi.TransportConfiguration;
import java.io.IOException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.apache.http.conn.ssl.SSLInitializationException;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.conn.PoolingClientConnectionManager;

class RedmineManagerFactoryHelper {
    /**
     * Implement a minimal hostname verifier, that delegates to the hostname
     * verifier used by the HTttpsURLCOnnection
     */
    private static PoolingClientConnectionManager createConnectionManager() throws SSLInitializationException {
        SSLSocketFactory socketFactory = SSLSocketFactory.getSystemSocketFactory();
        socketFactory.setHostnameVerifier(new X509HostnameVerifier() {
            @Override
            public void verify(String string, SSLSocket ssls) throws IOException {
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(string, ssls.getSession())) {
                    throw new SSLException("Hostname did not verify");
                }
            }
            
            @Override
            public void verify(String string, X509Certificate xc) throws SSLException {
                throw new SSLException("Check not implemented yet");
            }
            
            @Override
            public void verify(String string, String[] strings, String[] strings1) throws SSLException {
                throw new SSLException("Check not implemented yet");
            }
            
            @Override
            public boolean verify(String string, SSLSession ssls) {
                return HttpsURLConnection.getDefaultHostnameVerifier().verify(string, ssls);
            }
        });
        PoolingClientConnectionManager connectionManager = 
                com.taskadapter.redmineapi.RedmineManagerFactory
                        .createConnectionManager(Integer.MAX_VALUE, socketFactory);
        return connectionManager;
    }
    
    public static TransportConfiguration getTransportConfig() {
        return com.taskadapter.redmineapi.RedmineManagerFactory.createShortTermConfig(createConnectionManager());
    }
}
