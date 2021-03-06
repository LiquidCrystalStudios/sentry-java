package io.sentry.android.core;

import io.sentry.protocol.DebugImage;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class NoOpDebugImagesLoader implements IDebugImagesLoader {

  private static final NoOpDebugImagesLoader instance = new NoOpDebugImagesLoader();

  private NoOpDebugImagesLoader() {}

  public static NoOpDebugImagesLoader getInstance() {
    return instance;
  }

  @Override
  public @Nullable List<DebugImage> loadDebugImages() {
    return null;
  }

  @Override
  public void clearDebugImages() {}
}
