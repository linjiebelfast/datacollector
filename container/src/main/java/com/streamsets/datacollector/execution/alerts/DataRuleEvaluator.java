/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.execution.alerts;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.EvictingQueue;
import com.streamsets.datacollector.alerts.AlertsUtil;
import com.streamsets.datacollector.config.DataRuleDefinition;
import com.streamsets.datacollector.definition.ELDefinitionExtractor;
import com.streamsets.datacollector.el.ELEvaluator;
import com.streamsets.datacollector.el.ELVariables;
import com.streamsets.datacollector.el.ElConstantDefinition;
import com.streamsets.datacollector.el.ElFunctionDefinition;
import com.streamsets.datacollector.el.RuleELRegistry;
import com.streamsets.datacollector.execution.runner.common.Constants;
import com.streamsets.datacollector.execution.runner.common.SampledRecord;
import com.streamsets.datacollector.metrics.MetricsConfigurator;
import com.streamsets.datacollector.restapi.bean.CounterJson;
import com.streamsets.datacollector.restapi.bean.MetricRegistryJson;
import com.streamsets.datacollector.runner.LaneResolver;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.datacollector.util.ObserverException;
import com.streamsets.pipeline.api.Record;

import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.impl.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataRuleEvaluator {

  private static final Logger LOG = LoggerFactory.getLogger(DataRuleEvaluator.class);
  private static final String USER_PREFIX = "user.";

  private static final Map<String,Map<String, List<String>>> DATA_RULES_EL_DEFS = createDataRulesElDefs();

  public static final String PIPELINE_CONTEXT = "PIPELINE";
  public static final String RULE_ID_CONTEXT = "RULE_ID";


  private static List<String> createElFunctionIdx(String setName) {
    List<ElFunctionDefinition> defs = ELDefinitionExtractor.get().extractFunctions(
        RuleELRegistry.getRuleELs(setName),
        Utils.formatL("DataRules set '{}'", setName)
    );
    List<String> idx = new ArrayList<>();
    for (ElFunctionDefinition f : defs) {
      idx.add(f.getIndex());
    }
    return idx;
  }

  private static List<String> createElConstantIdx(String setName) {
    List<ElConstantDefinition> defs = ELDefinitionExtractor.get().extractConstants(
        RuleELRegistry.getRuleELs(setName),
        Utils.formatL("DataRules set '{}'", setName)
    );
    List<String> idx = new ArrayList<>();
    for (ElConstantDefinition f : defs) {
      idx.add(f.getIndex());
    }
    return idx;
  }

  private static Map<String,Map<String, List<String>>> createDataRulesElDefs() {
    Map<String,Map<String, List<String>>> defs = new HashMap<>();
    for (String setName : RuleELRegistry.getFamilies()) {
      Map<String, List<String>> setDefs = new HashMap<>();
      setDefs.put("elFunctionDefinitions", createElFunctionIdx(setName));
      setDefs.put("elConstantDefinitions", createElConstantIdx(setName));
      defs.put(setName, setDefs);
    }
    return defs;
  }

  private final MetricRegistry metrics;
  private final List<String> emailIds;
  private final Configuration configuration;
  private final Map<String, Object> pipelineELContext;
  private final DataRuleDefinition dataRuleDefinition;
  private final AlertManager alertManager;
  private final String name;
  private final String rev;
  private final MetricRegistryJson metricRegistryJson;


  public DataRuleEvaluator(
      String name, String rev, MetricRegistry metrics, AlertManager alertManager, List<String> emailIds,
      Map<String, Object> pipelineELContext, DataRuleDefinition dataRuleDefinition, Configuration configuration,
      MetricRegistryJson metricRegistryJson
  ) {
    this.name = name;
    this.rev = rev;
    this.metrics = metrics;
    this.emailIds = emailIds;
    this.pipelineELContext = pipelineELContext;
    this.dataRuleDefinition = dataRuleDefinition;
    this.configuration = configuration;
    this.alertManager = alertManager;
    this.metricRegistryJson = metricRegistryJson;
  }

  private DataRuleDefinition cloneRuleWithResolvedAlertText(DataRuleDefinition def, String alterInfo) {
    return new DataRuleDefinition(
        def.getFamily(),
        def.getId(),
        def.getLabel(),
        def.getLane(),
        def.getSamplingPercentage(),
        def.getSamplingRecordsToRetain(),
        def.getCondition(),
        def.isAlertEnabled(),
        alterInfo,
        def.getThresholdType(),
        def.getThresholdValue(),
        def.getMinVolume(),
        def.isMeterEnabled(),
        def.isSendEmail(),
        def.isAlertEnabled()
    );
  }

  public void evaluateRule(List<Record> sampleRecords, String lane,
      Map<String, EvictingQueue<SampledRecord>> ruleToSampledRecordsMap) {

    if (dataRuleDefinition.isEnabled() && sampleRecords != null && sampleRecords.size() > 0) {

      // initializing the ElVar context for the duration of the rule evalution to be able to have a 'rule' context
      // for the alert:info() EL.
      ELVariables elVars = new ELVariables();
      elVars.addContextVariable(PIPELINE_CONTEXT, pipelineELContext);
      elVars.addContextVariable(RULE_ID_CONTEXT, dataRuleDefinition.getId());

      //cache all sampled records for this data rule definition in an evicting queue
      EvictingQueue<SampledRecord> sampledRecords = ruleToSampledRecordsMap.get(dataRuleDefinition.getId());
      if (sampledRecords == null) {
        int maxSize = configuration.get(
            Constants.SAMPLED_RECORDS_MAX_CACHE_SIZE_KEY,
            Constants.SAMPLED_RECORDS_MAX_CACHE_SIZE_DEFAULT);
        int size = dataRuleDefinition.getSamplingRecordsToRetain();
        if (size > maxSize) {
          size = maxSize;
        }
        sampledRecords = EvictingQueue.create(size);
        ruleToSampledRecordsMap.put(dataRuleDefinition.getId(), sampledRecords);
      }
      //Meter
      //evaluate sample set of records for condition
      int matchingRecordCount = 0;
      int evaluatedRecordCount = 0;
      for (Record r : sampleRecords) {
        evaluatedRecordCount++;
        //evaluate
        boolean success = evaluate(elVars, r, dataRuleDefinition.getCondition(), dataRuleDefinition.getId());
        if (success) {
          sampledRecords.add(new SampledRecord(r, true));
          matchingRecordCount++;
        } else {
          sampledRecords.add(new SampledRecord(r, false));
        }
      }

      if (dataRuleDefinition.isAlertEnabled()) {
        //Keep the counters and meters ready before execution
        //batch record counter - cummulative sum of records per batch
        Counter evaluatedRecordCounter =
            MetricsConfigurator.getCounter(metrics, LaneResolver.getPostFixedLaneForObserver(
                lane));
        if (evaluatedRecordCounter == null) {
          evaluatedRecordCounter = MetricsConfigurator.createCounter(metrics, LaneResolver.getPostFixedLaneForObserver(
              lane), name, rev);
          if (metricRegistryJson != null) {
            CounterJson counterJson =
              metricRegistryJson.getCounters().get(
                LaneResolver.getPostFixedLaneForObserver(lane) + MetricsConfigurator.COUNTER_SUFFIX);
            evaluatedRecordCounter.inc(counterJson.getCount());
          }
        }
        //counter for the matching records - cummulative sum of records that match criteria
        Counter matchingRecordCounter =
            MetricsConfigurator.getCounter(metrics, USER_PREFIX + dataRuleDefinition.getId());
        if (matchingRecordCounter == null) {
          matchingRecordCounter =
            MetricsConfigurator.createCounter(metrics, USER_PREFIX + dataRuleDefinition.getId(), name, rev);
          if (metricRegistryJson != null) {
            CounterJson counterJson =
              metricRegistryJson.getCounters().get(
                USER_PREFIX + dataRuleDefinition.getId() + MetricsConfigurator.COUNTER_SUFFIX);
            matchingRecordCounter.inc(counterJson.getCount());
          }
        }

        evaluatedRecordCounter.inc(evaluatedRecordCount);
        matchingRecordCounter.inc(matchingRecordCount);

        double threshold;
        try {
          threshold = Double.parseDouble(dataRuleDefinition.getThresholdValue());
        } catch (NumberFormatException e) {
          //Soft error for now as we don't want this alert to stop other rules
          LOG.error("Error interpreting threshold '{}' as a number", dataRuleDefinition.getThresholdValue(), e);
          return;
        }
        switch (dataRuleDefinition.getThresholdType()) {
          case COUNT:
            if (matchingRecordCounter.getCount() > threshold) {
              alertManager.alert(
                  matchingRecordCounter.getCount(),
                  emailIds,
                  cloneRuleWithResolvedAlertText(dataRuleDefinition, resolveAlertText(elVars, dataRuleDefinition))
              );
            }
            break;
          case PERCENTAGE:
            if ((matchingRecordCounter.getCount() * 100.0 / evaluatedRecordCounter.getCount()) > threshold
                && evaluatedRecordCounter.getCount() >= dataRuleDefinition.getMinVolume()) {
              alertManager.alert(
                  matchingRecordCounter.getCount(),
                  emailIds,
                  cloneRuleWithResolvedAlertText(dataRuleDefinition, resolveAlertText(elVars, dataRuleDefinition))
              );
            }
            break;
        }
      }

      if (dataRuleDefinition.isMeterEnabled() && matchingRecordCount > 0) {
        Meter meter = MetricsConfigurator.getMeter(metrics, USER_PREFIX + dataRuleDefinition.getId());
        if (meter == null) {
          meter = MetricsConfigurator.createMeter(metrics, USER_PREFIX + dataRuleDefinition.getId(), name ,rev);
        }
        meter.mark(matchingRecordCount);
      }
    }
  }

  @VisibleForTesting
  boolean evaluate(ELVariables elVars, Record record, String el, String id) {
    try {
      return AlertsUtil.evaluateRecord(
          record,
          el,
          elVars,
          new ELEvaluator("el", RuleELRegistry.getRuleELs(dataRuleDefinition.getFamily()))
      );
    } catch (ObserverException e) {
      //A faulty condition should not take down rest of the alerts with it.
      //Log and it and continue for now
      LOG.error("Error processing rule definition '{}', reason: {}", id, e.toString(), e);

      //Trigger alert with exception message
      alertManager.alertException(e.toString(), dataRuleDefinition);

      return false;
    }
  }

  @VisibleForTesting
  String resolveAlertText(ELVars elVars, DataRuleDefinition ruleDef) {
    try {
      ELEvaluator elEval = new ELEvaluator("alertInfo", RuleELRegistry.getRuleELs(RuleELRegistry.ALERT));
      return elEval.eval(elVars, ruleDef.getAlertText(), String.class);
    } catch (ELEvalException e) {
      //A faulty el alerttext should not take down rest of the alerts with it.
      //Log and it and continue for now
      String msg = Utils.format(
          "Error resolving rule '{}' alert text '{}', reason: {}",
          ruleDef.getId(),
          ruleDef.getAlertText(),
          e.toString());
      LOG.error(msg, e);

      //Trigger alert with exception message
      alertManager.alertException(msg, dataRuleDefinition);

      return "[Could not resolve alert info]: " + ruleDef.getAlertText();
    }
  }

  @VisibleForTesting
  DataRuleDefinition getDataRuleDefinition() {
    return dataRuleDefinition;
  }

  public static Map<String,Map<String, List<String>>> getELDefinitions() {
    return DATA_RULES_EL_DEFS;
  }

}