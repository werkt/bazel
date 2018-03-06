// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.util;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.lang.StringBuilder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
*  Create a singleton class capable of storing compile commands are they are generated as
*  well as writing them to json format.
*/
public class CppCompileCommands {
  private static CppCompileCommands instance = null;
  private final Hashtable<String, CompileCommand> lookup = new Hashtable<String, CompileCommand>();
  private boolean logging;

  /**
  *  Create an object that can be serialized to json with GSON.
  */
  class CompileCommand implements JsonSerializer<CompileCommand> {
    private String directory;
    private String command;
    private String file;
    public CompileCommand(String directory, String command, String file){
      this.directory = directory;
      this.command = command;
      this.file = file;
    }
    public String getFile(){
      return file;
    }
    @Override
    public JsonElement serialize(CompileCommand src, Type typeOfSrc,
        JsonSerializationContext context){

      JsonObject obj = new JsonObject();
      obj.addProperty("directory", src.directory);
      obj.addProperty("command", src.command);
      obj.addProperty("file", src.file);

      return obj;
    }
  }

  /**
  * Stop normal construction of CppCompileCommands
  */
  private CppCompileCommands() {
    // Exists only to defeat instantiation.
  }

  /**
  * Get the singleton instance of CppCompileCommands
  */
  public static CppCompileCommands getInstance() {
    if(instance == null) {
      instance = new CppCompileCommands();
    }
    return instance;
  }

  /**
  * Are we logging compile commands?
  */
  public boolean logging() {
    return this.logging;
  }

  /**
  * Update logging for compile commands.
  * @param logging true if we are logging commands
  */
  public void setLogging(boolean logging) {
    this.logging = logging;
  }

  /**
  * Add a compile command action.
  * @param directory directory where this command should be executed.
  * @param command contents to be executed.
  * @param file name of file that is being compiled.
  */
  public synchronized void addAction(String directory, String command, String file) {
    lookup.put(file, new CompileCommand(directory, command, file));
  }

  /**
  *  Load and save the compile commands from this path. If the file
  *  exist new content will be added but old content will remain.
  *  @param path path of the file to write.
  */
  public void save(Reporter reporter, Path path) throws IOException {
    if (path == null){
      return;
    }

    // if this file exists parse it and add any new items to the lookup table.
    try {
      if (path.exists()) {
        // FIXME make this stream?
        String content = new String(Files.readAllBytes(Paths.get(path.getPathString())));
          CompileCommand[] items = new Gson().fromJson(content, CompileCommand[].class);
        for(CompileCommand cmd: items) {
          if (!lookup.containsKey(cmd.getFile())) {
            lookup.put(cmd.getFile(), cmd);
          }
        }
      }
    } catch (JsonSyntaxException e) {
      reporter.handle(Event.warn(null, "removing malformed compile_commands"));
      /* remove malformed json */
      path.delete();
    }

    // don't do anything if nothing was compiled
    // and we already have a valid output file.
    if (lookup.size() == 0 && path.exists()) {
      return;
    }

    GsonBuilder builder = new GsonBuilder();
    builder.setPrettyPrinting();
    builder.disableHtmlEscaping();
    Gson gson = builder.create();

    Path tmpPath = path.getParentDirectory().getChild(path.getBaseName() + "-tmp");

    try {
      JsonWriter writer = new JsonWriter(new OutputStreamWriter(tmpPath.getOutputStream(), "UTF-8"));
      writer.beginArray();
      for (CompileCommand c : lookup.values()) {
        writer.jsonValue(gson.toJson(c));
      }
      writer.endArray();

      writer.close();

      tmpPath.renameTo(path);
    } finally {
      if (tmpPath.exists()) {
        tmpPath.delete();
      }
    }
  }

  /**
  * Clear out any compile commands.
  */
  public void clear() {
    lookup.clear();
  }
}
