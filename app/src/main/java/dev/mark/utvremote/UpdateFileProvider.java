package dev.mark.utvremote;

/** Dedicated provider subclass recommended by AndroidX for device compatibility. */
public final class UpdateFileProvider extends androidx.core.content.FileProvider {
    public UpdateFileProvider() { super(); }
}
