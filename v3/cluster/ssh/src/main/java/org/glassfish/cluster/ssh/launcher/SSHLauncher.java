/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.cluster.ssh.launcher;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.KnownHosts;
import org.glassfish.cluster.ssh.util.HostVerifier;
import org.glassfish.cluster.ssh.util.SSHUtil;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import com.sun.enterprise.config.serverbeans.SshConnector;
import com.sun.enterprise.config.serverbeans.SshAuth;
import com.sun.enterprise.config.serverbeans.Node;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

@Service(name="SSHLauncher")
public class SSHLauncher {

    @Inject
    Habitat habitat;

  /**
     * Database of known hosts.
     */
    private static KnownHosts knownHostsDatabase = new KnownHosts();

  /**
     * The host name which to connect to via ssh
     */
    private String host;

  /**
     * The port on which the ssh daemon is running
     */
    private int port;

  /**
     * The user name to use for authenticating with the ssh daemon
     */
    private String userName;

  /**
     * The name of private key file.
     */
    private String keyFile;

  /**
     * The connection object that represents the connection to the host
     * via ssh
     */
    private Connection connection;

    private String authType;

    private File knownHosts;

    public void init(String nodeName) {
        Node node = habitat.getComponent(Node.class, nodeName);
        this.host = node.getHostNode();
        //Getting the first one right now. Do we really need a list to be
        // returned?
        SshConnector connector = node.getSshConnector().get(0);
        int port = Integer.parseInt(connector.getsshPort());
        this.port = port == 0 ? 22 : port;
        //Getting the first one right now. Do we really need a list to be
        // returned?        
        SshAuth sshAuth = connector.getSshAuth().get(0);
        String userName = sshAuth.getUserName();
        this.userName = SSHUtil.checkString(userName) == null ?
                    System.getProperty("user.name") : userName;
        System.setProperty("user.name", userName);
        this.keyFile = sshAuth.getKeyfile();
        // String authType = sshAuth.;
        //this.authType = authType == null ? "key" : authType;

        if (knownHosts == null) {
            File home = new File(System.getProperty("user.home"));
            knownHosts = new File(home,".ssh/known_hosts");
        }
        if (knownHosts.exists()) {
            try {
                knownHostsDatabase.addHostkeys(knownHosts);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

  /**
     * Opens the connection to the host and authenticates with public
     * key.
     * 
     */
    private boolean openConnection() {

        boolean isAuthenticated = false;
        connection = new Connection(host, port);
        try {
            connection.connect(new HostVerifier(knownHostsDatabase));
            String userName = this.userName;
            if(SSHUtil.checkString(keyFile)==null) {
                // check the default key locations if no authentication 
                // method is explicitly configured.
                File home = new File(System.getProperty("user.home"));
                for (String keyName : Arrays.asList("id_rsa","id_dsa",
                                                    "identity")) 
                {
                    File key = new File(home,".ssh/"+keyName);
                    if (key.exists()) {
                        isAuthenticated = 
                            connection.authenticateWithPublicKey(userName, 
                                                                 key, null);
                    }
                    if (isAuthenticated)
                        break;
                }
            }
            if (!isAuthenticated && SSHUtil.checkString(keyFile)!=null) {
                File key = new File(keyFile);
                if (key.exists()) {
                   isAuthenticated = connection.authenticateWithPublicKey(
                                                userName, key, null);

                }
            }

            if (!isAuthenticated && !connection.isAuthenticationComplete()) {
                connection.close();
                connection = null;
                throw new IOException("Could not authenticate");
            }
            SSHUtil.register(connection);


        } catch (IOException e) {
            e.printStackTrace();  
        }
        return isAuthenticated;
    }

    public void runCommand(String command) throws IOException, 
                    InterruptedException 
    {
        openConnection();
        connection.exec(command, 
                    new BufferedOutputStream(new ByteArrayOutputStream()));

        // XXX: Should we close connection after each command or cache it
        // and re-use it?
        SSHUtil.unregister(connection);
        connection = null;
    }
}
