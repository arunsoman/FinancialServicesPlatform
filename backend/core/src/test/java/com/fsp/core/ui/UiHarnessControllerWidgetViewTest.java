package com.fsp.core.ui;

import com.fsp.core.common.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The workbench registry sorts widgets by when they were first registered (created) — the
 *  list must carry createdAt, which a re-registration (upsert) never moves. */
class UiHarnessControllerWidgetViewTest {

    @Test
    void widgetListCarriesCreatedAtSeparateFromUpdatedAt() {
        WidgetRegistryService registry = mock(WidgetRegistryService.class);
        UiWidget w = new UiWidget();
        w.id = "approvals.queue";
        w.version = "1.2.0";
        w.label = "Approvals";
        w.bundle = "widgets/approvals-queue";
        w.portals = "[\"admin\"]";
        w.requires = "[\"je.approve\"]";
        w.status = "ACTIVE";
        w.registeredBy = UUID.randomUUID();
        w.createdAt = Instant.parse("2026-11-01T08:00:00Z");
        w.updatedAt = Instant.parse("2026-11-20T10:30:00Z");
        when(registry.list(any())).thenReturn(List.of(w));

        UiHarnessController controller = new UiHarnessController(registry, null, null, null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("fsp.actor")).thenReturn(new Actor(UUID.randomUUID(), "platform-admin", UUID.randomUUID()));

        List<UiDtos.WidgetView> views = controller.list(request);

        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.createdAt()).isEqualTo("2026-11-01T08:00:00Z");
            assertThat(v.updatedAt()).isEqualTo("2026-11-20T10:30:00Z");
        });
    }
}
