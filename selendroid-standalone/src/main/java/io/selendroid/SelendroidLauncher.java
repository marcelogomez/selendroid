/*
 * Copyright 2013 selendroid committers.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.selendroid;

import io.selendroid.exceptions.AndroidSdkException;
import io.selendroid.server.SelendroidServer;

import java.util.logging.Logger;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

public class SelendroidLauncher {
  private static final Logger log = Logger.getLogger(SelendroidLauncher.class.getName());
  private static SelendroidServer server = null;

  public static void main(String[] args) {
    log.info("################# Selendroid #################");
    SelendroidConfiguration config = new SelendroidConfiguration();
    try {
      new JCommander(config, args);
    } catch (ParameterException e) {
      log.severe("An errror occured while starting selendroid: " + e.getMessage());
      System.exit(0);
    }

    try {
      log.info("Starting selendroid-server port " + config.getPort());
      server = new SelendroidServer(config);
      server.start();
    } catch (AndroidSdkException e) {
      log.severe("Selendroid was not able to interact with the Android SDK: " + e.getMessage());
      log.severe("Please make sure you have the lastest version with the latest updates installed: ");
      log.severe("http://developer.android.com/sdk/index.html");
    } catch (Exception e) {
      log.severe("Error occured while building server: " + e.getMessage());
    }
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        log.info("Shutting down Selendroid standalone");
        if (server != null) {
          server.stop();
        }
      }
    });
  }
}
