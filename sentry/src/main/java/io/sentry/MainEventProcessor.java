package io.sentry;

import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.User;
import io.sentry.util.ApplyScopeUtils;
import io.sentry.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class MainEventProcessor implements EventProcessor {

  /**
   * Default value for {@link SentryEvent#getEnvironment()} set when both event and {@link
   * SentryOptions} do not have the environment field set.
   */
  private static final String DEFAULT_ENVIRONMENT = "production";

  private final @NotNull SentryOptions options;
  private final @NotNull SentryThreadFactory sentryThreadFactory;
  private final @NotNull SentryExceptionFactory sentryExceptionFactory;
  private final @Nullable HostnameCache hostnameCache;

  MainEventProcessor(final @NotNull SentryOptions options) {
    this(options, options.isAttachServerName() ? new HostnameCache() : null);
  }

  MainEventProcessor(
      final @NotNull SentryOptions options, final @Nullable HostnameCache hostnameCache) {
    this.options = Objects.requireNonNull(options, "The SentryOptions is required.");
    this.hostnameCache = hostnameCache;

    final SentryStackTraceFactory sentryStackTraceFactory =
        new SentryStackTraceFactory(
            this.options.getInAppExcludes(), this.options.getInAppIncludes());

    sentryExceptionFactory = new SentryExceptionFactory(sentryStackTraceFactory);
    sentryThreadFactory = new SentryThreadFactory(sentryStackTraceFactory, this.options);
  }

  MainEventProcessor(
      final @NotNull SentryOptions options,
      final @NotNull SentryThreadFactory sentryThreadFactory,
      final @NotNull SentryExceptionFactory sentryExceptionFactory,
      final @NotNull HostnameCache hostnameCache) {
    this.options = Objects.requireNonNull(options, "The SentryOptions is required.");
    this.sentryThreadFactory =
        Objects.requireNonNull(sentryThreadFactory, "The SentryThreadFactory is required.");
    this.sentryExceptionFactory =
        Objects.requireNonNull(sentryExceptionFactory, "The SentryExceptionFactory is required.");
    this.hostnameCache = Objects.requireNonNull(hostnameCache, "The HostnameCache is required");
  }

  @Override
  public @NotNull SentryEvent process(
      final @NotNull SentryEvent event, final @Nullable Object hint) {
    setCommons(event);
    setExceptions(event);

    if (shouldApplyScopeData(event, hint)) {
      processNonCachedEvent(event);
      setThreads(event);
    }

    return event;
  }

  private boolean shouldApplyScopeData(
      final @NotNull SentryBaseEvent event, final @Nullable Object hint) {
    if (ApplyScopeUtils.shouldApplyScopeData(hint)) {
      return true;
    } else {
      options
          .getLogger()
          .log(
              SentryLevel.DEBUG,
              "Event was cached so not applying data relevant to the current app execution/version: %s",
              event.getEventId());
      return false;
    }
  }

  private void processNonCachedEvent(final @NotNull SentryBaseEvent event) {
    setRelease(event);
    setEnvironment(event);
    setServerName(event);
    setDist(event);
    setSdk(event);
    setTags(event);
    mergeUser(event);
  }

  @Override
  public @NotNull SentryTransaction process(
      final @NotNull SentryTransaction transaction, final @Nullable Object hint) {
    setCommons(transaction);

    if (shouldApplyScopeData(transaction, hint)) {
      processNonCachedEvent(transaction);
    }

    return transaction;
  }

  private void setCommons(final @NotNull SentryBaseEvent event) {
    setPlatform(event);
  }

  private void setPlatform(final @NotNull SentryBaseEvent event) {
    if (event.getPlatform() == null) {
      // this actually means JVM related.
      event.setPlatform(SentryBaseEvent.DEFAULT_PLATFORM);
    }
  }

  private void setRelease(final @NotNull SentryBaseEvent event) {
    if (event.getRelease() == null) {
      event.setRelease(options.getRelease());
    }
  }

  private void setEnvironment(final @NotNull SentryBaseEvent event) {
    if (event.getEnvironment() == null) {
      event.setEnvironment(
          options.getEnvironment() != null ? options.getEnvironment() : DEFAULT_ENVIRONMENT);
    }
  }

  private void setServerName(final @NotNull SentryBaseEvent event) {
    if (event.getServerName() == null) {
      event.setServerName(options.getServerName());
    }

    if (options.isAttachServerName() && hostnameCache != null && event.getServerName() == null) {
      event.setServerName(hostnameCache.getHostname());
    }
  }

  private void setDist(final @NotNull SentryBaseEvent event) {
    if (event.getDist() == null) {
      event.setDist(options.getDist());
    }
  }

  private void setSdk(final @NotNull SentryBaseEvent event) {
    if (event.getSdk() == null) {
      event.setSdk(options.getSdkVersion());
    }
  }

  private void setTags(final @NotNull SentryBaseEvent event) {
    if (event.getTags() == null) {
      event.setTags(new HashMap<>(options.getTags()));
    } else {
      for (Map.Entry<String, String> item : options.getTags().entrySet()) {
        if (!event.getTags().containsKey(item.getKey())) {
          event.setTag(item.getKey(), item.getValue());
        }
      }
    }
  }

  private void mergeUser(final @NotNull SentryBaseEvent event) {
    if (options.isSendDefaultPii()) {
      if (event.getUser() == null) {
        final User user = new User();
        user.setIpAddress(IpAddressUtils.DEFAULT_IP_ADDRESS);
        event.setUser(user);
      } else if (event.getUser().getIpAddress() == null) {
        event.getUser().setIpAddress(IpAddressUtils.DEFAULT_IP_ADDRESS);
      }
    }
  }

  private void setExceptions(final @NotNull SentryEvent event) {
    final Throwable throwable = event.getThrowable();
    if (throwable != null) {
      event.setExceptions(sentryExceptionFactory.getSentryExceptions(throwable));
    }
  }

  private void setThreads(final @NotNull SentryEvent event) {
    if (event.getThreads() == null) {
      // collecting threadIds that came from the exception mechanism, so we can mark threads as
      // crashed properly
      List<Long> mechanismThreadIds = null;

      final List<SentryException> eventExceptions = event.getExceptions();

      if (eventExceptions != null && !eventExceptions.isEmpty()) {
        for (final SentryException item : eventExceptions) {
          if (item.getMechanism() != null && item.getThreadId() != null) {
            if (mechanismThreadIds == null) {
              mechanismThreadIds = new ArrayList<>();
            }
            mechanismThreadIds.add(item.getThreadId());
          }
        }
      }

      if (options.isAttachThreads()) {
        event.setThreads(sentryThreadFactory.getCurrentThreads(mechanismThreadIds));
      } else if (options.isAttachStacktrace()
          && (eventExceptions == null || eventExceptions.isEmpty())) {
        // when attachStacktrace is enabled, we attach only the current thread and its stack traces,
        // if there are no exceptions, exceptions have its own stack traces.
        event.setThreads(sentryThreadFactory.getCurrentThread());
      }
    }
  }
}
