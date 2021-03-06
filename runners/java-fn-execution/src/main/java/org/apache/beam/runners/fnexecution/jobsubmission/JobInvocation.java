/*
 * Licensed to the Apache Software Foundation (ASF) under one
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
package org.apache.beam.runners.fnexecution.jobsubmission;

import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Throwables.getRootCause;
import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Throwables.getStackTraceAsString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobMessage;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobState;
import org.apache.beam.model.jobmanagement.v1.JobApi.JobState.Enum;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.vendor.guava.v20_0.com.google.common.util.concurrent.FutureCallback;
import org.apache.beam.vendor.guava.v20_0.com.google.common.util.concurrent.Futures;
import org.apache.beam.vendor.guava.v20_0.com.google.common.util.concurrent.ListenableFuture;
import org.apache.beam.vendor.guava.v20_0.com.google.common.util.concurrent.ListeningExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal representation of a Job which has been invoked (prepared and run) by a client. */
public class JobInvocation {

  private static final Logger LOG = LoggerFactory.getLogger(JobInvocation.class);

  private final RunnerApi.Pipeline pipeline;
  private final PortablePipelineRunner pipelineRunner;
  private final String id;
  private final ListeningExecutorService executorService;
  private List<Consumer<Enum>> stateObservers;
  private List<Consumer<JobMessage>> messageObservers;
  private JobState.Enum jobState;
  @Nullable private ListenableFuture<PipelineResult> invocationFuture;

  public JobInvocation(
      String id,
      ListeningExecutorService executorService,
      Pipeline pipeline,
      PortablePipelineRunner pipelineRunner) {
    this.id = id;
    this.executorService = executorService;
    this.pipeline = pipeline;
    this.pipelineRunner = pipelineRunner;
    this.stateObservers = new ArrayList<>();
    this.messageObservers = new ArrayList<>();
    this.invocationFuture = null;
    this.jobState = JobState.Enum.STOPPED;
  }

  private PipelineResult runPipeline() throws Exception {
    return pipelineRunner.run(pipeline);
  }

  /** Start the job. */
  public synchronized void start() {
    LOG.info("Starting job invocation {}", getId());
    if (getState() != JobState.Enum.STOPPED) {
      throw new IllegalStateException(String.format("Job %s already running.", getId()));
    }
    setState(JobState.Enum.STARTING);
    invocationFuture = executorService.submit(this::runPipeline);
    // TODO: Defer transitioning until the pipeline is up and running.
    setState(JobState.Enum.RUNNING);
    Futures.addCallback(
        invocationFuture,
        new FutureCallback<PipelineResult>() {
          @Override
          public void onSuccess(@Nullable PipelineResult pipelineResult) {
            if (pipelineResult != null) {
              checkArgument(
                  pipelineResult.getState() == PipelineResult.State.DONE,
                  "Success on non-Done state: " + pipelineResult.getState());
              setState(JobState.Enum.DONE);
            } else {
              setState(JobState.Enum.UNSPECIFIED);
            }
          }

          @Override
          public void onFailure(Throwable throwable) {
            String message = String.format("Error during job invocation %s.", getId());
            LOG.error(message, throwable);
            sendMessage(
                JobMessage.newBuilder()
                    .setMessageText(getStackTraceAsString(throwable))
                    .setImportance(JobMessage.MessageImportance.JOB_MESSAGE_DEBUG)
                    .build());
            sendMessage(
                JobMessage.newBuilder()
                    .setMessageText(getRootCause(throwable).toString())
                    .setImportance(JobMessage.MessageImportance.JOB_MESSAGE_ERROR)
                    .build());
            setState(JobState.Enum.FAILED);
          }
        },
        executorService);
  }

  /** @return Unique identifier for the job invocation. */
  public String getId() {
    return id;
  }

  /** Cancel the job. */
  public synchronized void cancel() {
    LOG.info("Canceling job invocation {}", getId());
    if (this.invocationFuture != null) {
      this.invocationFuture.cancel(true /* mayInterruptIfRunning */);
      Futures.addCallback(
          invocationFuture,
          new FutureCallback<PipelineResult>() {
            @Override
            public void onSuccess(@Nullable PipelineResult pipelineResult) {
              if (pipelineResult != null) {
                try {
                  pipelineResult.cancel();
                } catch (IOException exn) {
                  throw new RuntimeException(exn);
                }
              }
            }

            @Override
            public void onFailure(Throwable throwable) {}
          },
          executorService);
    }
  }

  /** Retrieve the job's current state. */
  public JobState.Enum getState() {
    return this.jobState;
  }

  /** Listen for job state changes with a {@link Consumer}. */
  public synchronized void addStateListener(Consumer<JobState.Enum> stateStreamObserver) {
    stateStreamObserver.accept(getState());
    stateObservers.add(stateStreamObserver);
  }

  /** Listen for job messages with a {@link Consumer}. */
  public synchronized void addMessageListener(Consumer<JobMessage> messageStreamObserver) {
    messageObservers.add(messageStreamObserver);
  }

  private synchronized void setState(JobState.Enum state) {
    this.jobState = state;
    for (Consumer<JobState.Enum> observer : stateObservers) {
      observer.accept(state);
    }
  }

  private synchronized void sendMessage(JobMessage message) {
    for (Consumer<JobMessage> observer : messageObservers) {
      observer.accept(message);
    }
  }

  static Boolean isTerminated(Enum state) {
    switch (state) {
      case DONE:
      case FAILED:
      case CANCELLED:
      case DRAINED:
        return true;
      default:
        return false;
    }
  }
}
