// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.ui.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.microsoft.copilot.eclipse.core.Constants;
import com.microsoft.copilot.eclipse.core.CopilotCore;
import com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServerConnection;
import com.microsoft.copilot.eclipse.ui.CopilotUi;
import com.microsoft.copilot.eclipse.ui.preferences.CopilotPreferenceInitializer;

class McpUtilsTests {

  @Test
  void testGetMcpRegistryBaseUrl_localPreference_doesNotRequestOrganizationAllowlist() {
    CopilotCore plugin = mock(CopilotCore.class);
    CopilotLanguageServerConnection connection = mock(CopilotLanguageServerConnection.class);
    when(plugin.getCopilotLanguageServer()).thenReturn(connection);

    try (MockedStatic<CopilotUi> ui = Mockito.mockStatic(CopilotUi.class);
        MockedStatic<CopilotCore> core = Mockito.mockStatic(CopilotCore.class)) {
      core.when(CopilotCore::getPlugin).thenReturn(plugin);
      ui.when(() -> CopilotUi.getStringPreference(Constants.MCP_REGISTRY_URL,
          CopilotPreferenceInitializer.DEFAULT_MCP_REGISTRY_BASE_URL))
          .thenReturn("https://local.example/registry/v0.1/servers/");

      assertEquals("https://local.example/registry", McpUtils.getMcpRegistryBaseUrl());
      verifyNoInteractions(connection);
    }
  }

  @Test
  void testGetMcpRegistryBaseUrl_emptyPreference_remainsEmpty() {
    try (MockedStatic<CopilotUi> ui = Mockito.mockStatic(CopilotUi.class)) {
      ui.when(() -> CopilotUi.getStringPreference(Constants.MCP_REGISTRY_URL,
          CopilotPreferenceInitializer.DEFAULT_MCP_REGISTRY_BASE_URL)).thenReturn("");

      assertEquals("", McpUtils.getMcpRegistryBaseUrl());
    }
  }
}
