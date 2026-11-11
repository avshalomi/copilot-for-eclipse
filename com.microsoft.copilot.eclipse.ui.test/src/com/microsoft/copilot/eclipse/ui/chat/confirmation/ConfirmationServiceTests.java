// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.ui.chat.confirmation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.microsoft.copilot.eclipse.core.Constants;
import com.microsoft.copilot.eclipse.core.CopilotCore;
import com.microsoft.copilot.eclipse.core.FeatureFlags;
import com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageClient;
import com.microsoft.copilot.eclipse.core.lsp.protocol.DidChangeFeatureFlagsParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.InvokeClientToolConfirmationParams;
import com.microsoft.copilot.eclipse.core.lsp.protocol.policy.DidChangePolicyParams;

class ConfirmationServiceTests {

  @Test
  void testEvaluate_restrictiveOrganizationSettings_localChoicesControlApproval() {
    PreferenceStore store = new PreferenceStore();
    ConfirmationService service = new ConfirmationService(store, new AttachedFileRegistry());
    CopilotCore plugin = mock(CopilotCore.class);
    when(plugin.getFeatureFlags()).thenReturn(new FeatureFlags());

    try (MockedStatic<CopilotCore> core = Mockito.mockStatic(CopilotCore.class)) {
      core.when(CopilotCore::getPlugin).thenReturn(plugin);
      CopilotLanguageClient client = new CopilotLanguageClient();
      DidChangeFeatureFlagsParams token = new DidChangeFeatureFlagsParams();
      token.setFeatureFlags(Map.of("mcp", "0", "agent_mode_auto_approval", "0"));
      client.onDidChangeFeatureFlags(token);
      DidChangePolicyParams policy = new DidChangePolicyParams();
      policy.setAutoApprovalPolicyEnabled(false);
      client.onDidChangePolicy(policy);

      InvokeClientToolConfirmationParams params = new InvokeClientToolConfirmationParams();
      params.setName("exampleTool");
      params.setInput(Map.of("toolType", "unknown"));
      assertFalse(service.evaluate(params, "conversation").isAutoApproved());

      store.setValue(Constants.AUTO_APPROVE_YOLO_MODE, true);
      assertTrue(service.evaluate(params, "conversation").isAutoApproved());
      store.setValue(Constants.AUTO_APPROVE_YOLO_MODE, false);
      assertFalse(service.evaluate(params, "conversation").isAutoApproved());

      params.setInput(Map.of("toolType", "mcp_tool", "mcpServerName", "localServer", "mcpToolName", "tool"));
      assertFalse(service.evaluate(params, "conversation").isAutoApproved());
      store.setValue(Constants.AUTO_APPROVE_MCP_SERVERS, "[\"localServer\"]");
      assertTrue(service.evaluate(params, "conversation").isAutoApproved());
      store.setValue(Constants.AUTO_APPROVE_MCP_SERVERS, "[]");
      assertFalse(service.evaluate(params, "conversation").isAutoApproved());
    }
  }
}
