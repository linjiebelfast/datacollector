/*
 * Copyright 2019 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.startPipeline;

import com.streamsets.pipeline.api.ConfigDefBean;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.Processor;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.base.configurablestage.DProcessor;
import com.streamsets.pipeline.lib.startPipeline.Groups;
import com.streamsets.pipeline.lib.startPipeline.StartPipelineConfig;

@StageDef(
    version = 1,
    label = "Start Pipeline",
    description = "Starts a Data Collector, Transformer, or Edge pipeline",
    icon="pipeline.png",
    execution = {
        ExecutionMode.STANDALONE
    },
    beta = true,
    onlineHelpRefUrl ="index.html?contextID=task_yh1_wxr_2jb"
)
@GenerateResourceBundle
@ConfigGroups(Groups.class)
public class StartPipelineDProcessor extends DProcessor {

  @ConfigDefBean
  public StartPipelineConfig conf;

  @Override
  protected Processor createProcessor() {
    return new StartPipelineProcessor(conf);
  }

}
