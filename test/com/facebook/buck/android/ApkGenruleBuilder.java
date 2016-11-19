/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;

import java.util.Optional;

public class ApkGenruleBuilder
    extends AbstractNodeBuilder<ApkGenruleDescription.Arg, ApkGenruleDescription> {

  private ApkGenruleBuilder(BuildTarget target) {
    super(new ApkGenruleDescription(), target);
  }

  public static ApkGenruleBuilder create(BuildTarget target) {
    return new ApkGenruleBuilder(target);
  }

  public ApkGenruleBuilder setOut(String out) {
    arg.out = out;
    return this;
  }

  public ApkGenruleBuilder setCmd(String cmd) {
    arg.cmd = Optional.of(cmd);
    return this;
  }

  public ApkGenruleBuilder setApk(BuildTarget apk) {
    arg.apk = apk;
    return this;
  }

}