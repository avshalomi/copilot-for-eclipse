// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;

import com.microsoft.copilot.eclipse.core.lsp.protocol.ChatProgressValue;
import com.microsoft.copilot.eclipse.core.lsp.protocol.ConversationError;

/**
 * Retries chat requests before any response or tool activity, including failures delivered through progress events.
 * Initial progress is buffered so failed attempts do not create duplicate turns in the UI or persisted history.
 */
public final class ChatRequestRetry<T> {
  public static final int DEFAULT_MAX_RETRIES = 3;
  public static final int DEFAULT_INTERVAL_SECONDS = 5;
  public static final int MAX_RETRIES = 100;
  public static final int MAX_INTERVAL_SECONDS = 3600;

  private static final int MAX_BUFFERED_EVENTS = 32;
  private static final int MAX_RETIRED_TOKENS = 1024;
  private static final Pattern HTTP_STATUS = Pattern.compile("\\b([45]\\d{2})\\b");
  private static final Map<String, ChatRequestRetry<?>> ACTIVE = new ConcurrentHashMap<>();
  private static final Set<ChatRequestRetry<?>> REQUESTS = ConcurrentHashMap.newKeySet();
  private static final Set<String> RETIRED = new LinkedHashSet<>();
  private static ScheduledThreadPoolExecutor scheduler;

  private final Function<String, CompletableFuture<T>> request;
  private final int maxRetries;
  private final long intervalMillis;
  private final CompletableFuture<T> result = new CompletableFuture<>();
  private final List<ChatProgressValue> buffered = new ArrayList<>();
  private CompletableFuture<T> currentRequest;
  private ScheduledFuture<?> pendingRetry;
  private Consumer<ChatProgressValue> deliver;
  private String token;
  private String conversationId;
  private int retries;
  private boolean outputStarted;
  private boolean ended;
  private boolean acknowledged;
  private T response;
  private ConversationError progressError;

  private ChatRequestRetry(Function<String, CompletableFuture<T>> request, int maxRetries, long intervalMillis) {
    this.request = request;
    this.maxRetries = Math.max(0, Math.min(MAX_RETRIES, maxRetries));
    this.intervalMillis = Math.max(0, Math.min(TimeUnit.SECONDS.toMillis(MAX_INTERVAL_SECONDS), intervalMillis));
    result.whenComplete((value, error) -> cleanup());
  }

  /**
   * Sends the request and completes after its final response and terminal progress event. Cancelling the returned
   * future cancels both the active request and any scheduled retry. maxRetries excludes the initial attempt.
   */
  public static <T> CompletableFuture<T> execute(String token, Function<String, CompletableFuture<T>> request,
      int maxRetries, long intervalMillis) {
    ChatRequestRetry<T> retry = new ChatRequestRetry<>(request, maxRetries, intervalMillis);
    REQUESTS.add(retry);
    retry.attempt(token);
    return retry.result;
  }

  private void attempt(String attemptToken) {
    synchronized (this) {
      if (result.isDone()) {
        return;
      }
      token = attemptToken;
      conversationId = null;
      buffered.clear();
      progressError = null;
      outputStarted = false;
      ended = false;
      acknowledged = false;
      response = null;
      ACTIVE.put(token, this);
    }
    try {
      CompletableFuture<T> submitted = request.apply(attemptToken);
      synchronized (this) {
        if (result.isDone() || !attemptToken.equals(token)) {
          submitted.cancel(false);
          return;
        }
        currentRequest = submitted;
      }
      submitted.whenComplete((value, error) -> onResponse(attemptToken, value, error));
    } catch (RuntimeException error) {
      onResponse(attemptToken, null, error);
    }
  }

  private void onResponse(String attemptToken, T value, Throwable error) {
    List<ChatProgressValue> events;
    Consumer<ChatProgressValue> consumer;
    Throwable cause = error == null ? null : unwrap(error);
    if (cause == null && value == null) {
      cause = new IOException("Chat service returned an empty response");
    }
    synchronized (this) {
      if (result.isDone() || !attemptToken.equals(token)) {
        return;
      }
      if (cause != null) {
        if (retry(isRetryable(cause))) {
          return;
        }
        ended = true;
      } else {
        acknowledged = true;
        response = value;
        if (!ended) {
          return;
        }
      }
      events = drainProgress();
      consumer = deliver;
    }
    // Listeners may marshal synchronously to SWT; never call them while holding the request lock.
    if (consumer != null) {
      events.forEach(consumer);
    }
    if (cause instanceof CancellationException) {
      result.cancel(false);
    } else if (cause != null) {
      result.completeExceptionally(cause);
    } else {
      result.complete(value);
    }
  }

  /** Returns true when this event was buffered or belongs to an abandoned attempt and must not reach listeners. */
  public static boolean interceptProgress(String token, ChatProgressValue value, Consumer<ChatProgressValue> deliver) {
    ChatRequestRetry<?> retry = ACTIVE.get(token);
    if (retry != null) {
      return retry.onProgress(token, value, deliver);
    }
    synchronized (RETIRED) {
      return RETIRED.contains(token);
    }
  }

  private boolean onProgress(String attemptToken, ChatProgressValue value,
      Consumer<ChatProgressValue> consumer) {
    List<ChatProgressValue> events;
    boolean complete = false;
    T completedResponse = null;
    synchronized (this) {
      if (result.isDone() || !attemptToken.equals(token)) {
        return true;
      }
      deliver = consumer;
      boolean subagent = value.getParentTurnId() != null && !value.getParentTurnId().isBlank();
      if (value.getConversationId() != null && !subagent) {
        conversationId = value.getConversationId();
      }
      // Subagent activity or any streamed output means replaying the request may duplicate work.
      outputStarted |= hasOutput(value) || subagent;
      if (value.getConversationError() != null && !subagent) {
        progressError = value.getConversationError();
      }
      if (value.getKind() == WorkDoneProgressKind.end && !subagent) {
        if (progressError != null && !progressError.getResponseIsFiltered()
            && !progressError.getResponseIsIncomplete()
            && (value.getCancellationReason() == null || value.getCancellationReason().isBlank())
            && retry(isRetryable(progressError.getCode(), progressError.getReason(), progressError.getMessage()))) {
          return true;
        }
        if (value.getConversationError() == null && progressError != null) {
          value.setConversationError(progressError);
        }
        ended = true;
        complete = acknowledged;
        completedResponse = response;
      } else if (!outputStarted) {
        if (buffered.size() >= MAX_BUFFERED_EVENTS) {
          // Keep the begin event and the latest status reports without accumulating unbounded metadata.
          buffered.remove(1);
        }
        buffered.add(value);
        return true;
      }
      events = drainProgress();
    }
    events.forEach(consumer);
    if (complete) {
      result.complete(completedResponse);
    }
    return false;
  }

  private boolean retry(boolean retryable) {
    if (!retryable || outputStarted || ended || retries >= maxRetries || result.isDone()) {
      return false;
    }
    retries++;
    retireToken();
    token = UUID.randomUUID().toString();
    String nextToken = token;
    buffered.clear();
    if (currentRequest != null) {
      currentRequest.cancel(false);
    }
    pendingRetry = scheduler().schedule(() -> attempt(nextToken), intervalMillis, TimeUnit.MILLISECONDS);
    return true;
  }

  private List<ChatProgressValue> drainProgress() {
    List<ChatProgressValue> events = new ArrayList<>(buffered);
    buffered.clear();
    return events;
  }

  /** Disables replay before invoking or confirming a tool, even if its progress report has not arrived yet. */
  public static void markToolActivity(String conversationId) {
    if (conversationId == null) {
      return;
    }
    for (ChatRequestRetry<?> retry : new LinkedHashSet<>(ACTIVE.values())) {
      retry.onToolActivity(conversationId);
    }
  }

  private void onToolActivity(String id) {
    List<ChatProgressValue> events;
    Consumer<ChatProgressValue> consumer;
    synchronized (this) {
      if (!id.equals(conversationId)) {
        return;
      }
      outputStarted = true;
      events = drainProgress();
      consumer = deliver;
    }
    events.forEach(consumer);
  }

  private synchronized void cleanup() {
    REQUESTS.remove(this);
    retireToken();
    if (pendingRetry != null) {
      pendingRetry.cancel(false);
    }
    if (currentRequest != null && !currentRequest.isDone()) {
      currentRequest.cancel(false);
    }
    buffered.clear();
  }

  private void retireToken() {
    if (token == null) {
      return;
    }
    // Mark retired before removal so late events cannot slip through the gap.
    synchronized (RETIRED) {
      RETIRED.add(token);
      while (RETIRED.size() > MAX_RETIRED_TOKENS) {
        RETIRED.remove(RETIRED.iterator().next());
      }
    }
    ACTIVE.remove(token, this);
  }

  /** Cancels outstanding requests and releases the retry worker when the language server stops. */
  public static void cancelAll() {
    for (ChatRequestRetry<?> retry : new LinkedHashSet<>(REQUESTS)) {
      retry.result.cancel(false);
    }
    synchronized (ChatRequestRetry.class) {
      if (scheduler != null) {
        scheduler.shutdownNow();
        scheduler = null;
      }
    }
  }

  private static synchronized ScheduledThreadPoolExecutor scheduler() {
    if (scheduler == null) {
      scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread worker = new Thread(runnable, "Copilot chat retry");
        worker.setDaemon(true);
        return worker;
      });
      scheduler.setRemoveOnCancelPolicy(true);
    }
    return scheduler;
  }

  private static boolean hasOutput(ChatProgressValue value) {
    return (value.getReply() != null && !value.getReply().isEmpty())
        || (value.getThinking() != null && value.getThinking().text() != null
            && !value.getThinking().text().isEmpty())
        || (value.getAgentRounds() != null && value.getAgentRounds().stream().anyMatch(round ->
            (round.getReply() != null && !round.getReply().isEmpty())
                || (round.getToolCalls() != null && !round.getToolCalls().isEmpty())));
  }

  static boolean isRetryable(Throwable error) {
    Throwable cause = unwrap(error);
    if (cause instanceof CancellationException || cause instanceof InterruptedException) {
      return false;
    }
    if (cause instanceof ResponseErrorException rpcError) {
      return isRetryable(rpcError.getResponseError().getCode(), null,
          rpcError.getResponseError().getMessage() + " " + rpcError.getResponseError().getData());
    }
    return cause instanceof TimeoutException || cause instanceof IOException
        || isRetryable(0, null, cause.getMessage());
  }

  static boolean isRetryable(int code, String reason, String message) {
    String text = ((reason == null ? "" : reason) + " " + (message == null ? "" : message))
        .toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*(model_not_supported|content.?filter|quota|unauthori[sz]ed|invalid.api.key|"
        + "authentication|permission.denied|context.length|context.window|token.limit|cancelled|canceled).*")) {
      return false;
    }
    if (code == 401 || code == 402 || code == 403 || code == 407) {
      return false;
    }
    Matcher status = HTTP_STATUS.matcher(text);
    if ((code < 400 || code > 599) && status.find()) {
      code = Integer.parseInt(status.group(1));
    }
    if (code >= 400 && code <= 599) {
      return code == 400 || code == 404 || code == 408 || code == 409 || code == 425 || code == 429 || code >= 500;
    }
    return text.matches("(?s).*(timeout|timed.out|network|proxy|fetch.failed|econnreset|econnrefused|etimedout|"
        + "enotfound|eai_again|socket|connection|temporar|unavailable|bad.gateway|overloaded).*");
  }

  private static Throwable unwrap(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
      error = error.getCause();
    }
    return error;
  }
}
