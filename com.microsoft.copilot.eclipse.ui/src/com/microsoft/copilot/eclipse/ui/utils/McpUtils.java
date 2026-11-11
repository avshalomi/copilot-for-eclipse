// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.ui.utils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.swt.graphics.Image;

import com.microsoft.copilot.eclipse.core.Constants;
import com.microsoft.copilot.eclipse.core.lsp.mcp.registry.Icon;
import com.microsoft.copilot.eclipse.ui.CopilotImages;
import com.microsoft.copilot.eclipse.ui.CopilotUi;
import com.microsoft.copilot.eclipse.ui.preferences.CopilotPreferenceInitializer;

/**
 * Utility class for handling MCP related operations.
 */
public class McpUtils {
  /**
   * Returns the user's MCP registry URL, independent of organization registry restrictions.
   *
   * @return the configured registry base URL without a version suffix
   */
  public static String getMcpRegistryBaseUrl() {
    String localUrl = CopilotUi.getStringPreference(Constants.MCP_REGISTRY_URL,
        CopilotPreferenceInitializer.DEFAULT_MCP_REGISTRY_BASE_URL);
    return extractBaseUrl(localUrl.replaceAll("/+$", ""));
  }

  /**
   * Removes version suffixes such as /v0.1/servers from a registry URL.
   *
   * @param fullUrl the full URL
   * @return the base URL without a version suffix
   */
  public static String extractBaseUrl(String fullUrl) {
    if (fullUrl == null) {
      return null;
    }
    return fullUrl.replaceAll("/v\\d+(\\.\\d+)*/servers$", "");
  }

  /**
   * Gets the preferred icon URL from the given icon list. Prefers PNG format over other formats since SWT has better
   * support for raster images.
   *
   * @param icons the list of icons from an MCP server definition
   * @return the icon URL, or null if no suitable icon is found
   */
  public static String getPreferredIconUrl(List<Icon> icons) {
    if (icons == null || icons.isEmpty()) {
      return null;
    }

    for (Icon icon : icons) {
      if (icon != null && icon.src() != null && "image/png".equalsIgnoreCase(icon.mimeType())) {
        return icon.src();
      }
    }
    for (Icon icon : icons) {
      if (icon != null && icon.src() != null && !"image/svg+xml".equalsIgnoreCase(icon.mimeType())) {
        return icon.src();
      }
    }
    return null;
  }

  /**
   * Loads the server icon from the given URL, scaled to the requested size.
   *
   * <p>The returned image must be disposed by the caller, i.e. by attaching it to a widget and disposing the image
   * using a widget dispose listener. When no icon can be loaded (blank URL or load failure) the future completes
   * with {@code null}. In that case callers should fall back to {@link #loadDefaultServerIcon()},
   * whose image is registry-owned and must never be disposed.</p>
   *
   * @param iconUrl the icon URL to load
   * @param width desired icon width in pixels
   * @param height desired icon height in pixels
   * @return a future that completes with the loaded, caller-owned image, or {@code null} if the image cannot be loaded
   */
  public static CompletableFuture<Image> loadDisposableServerIcon(String iconUrl, int width, int height) {
    if (StringUtils.isBlank(iconUrl)) {
      return CompletableFuture.completedFuture(null);
    }

    return UiUtils.loadImageFromUrl(iconUrl, width, height)
        .exceptionally(e -> null);
  }

  /**
   * Loads the shared default MCP server icon.
   * The image must not be disposed by the caller.
   *
   * @return the default MCP icon image; callers must not dispose it
   */
  public static Image loadDefaultServerIcon() {
    return CopilotImages.getImage(CopilotImages.IMG_MCP_DEFAULT_ICON);
  }
}
