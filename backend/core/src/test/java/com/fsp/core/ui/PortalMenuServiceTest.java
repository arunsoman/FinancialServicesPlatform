package com.fsp.core.ui;

import com.fsp.core.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/** A portal's menus are its published layout plus the active widgets that work in it — read live, never signed. */
class PortalMenuServiceTest {

    private final UiLayoutRepository layouts = Mockito.mock(UiLayoutRepository.class);
    private final UiWidgetRepository widgets = Mockito.mock(UiWidgetRepository.class);
    private final PortalMenuService service = new PortalMenuService(layouts, widgets);

    private static UiWidget widget(String id, String portals, String status) {
        UiWidget w = new UiWidget();
        w.id = id;
        w.version = "1.0.0";
        w.bundle = "widgets/" + id;
        w.portals = portals;
        w.requires = "[\"invoice.view\"]";
        w.status = status;
        return w;
    }

    @Test
    void servesThePublishedMenusAndOnlyThisPortalsActiveWidgets() {
        UiLayout l = new UiLayout();
        l.id = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
        l.portal = "merchant";
        l.status = UiLayout.Status.PUBLISHED;
        l.layoutJson = "{\"portal\":\"merchant\",\"menus\":[{\"id\":\"billing\",\"label\":\"Billing\",\"screens\":[{\"id\":\"invoices\",\"label\":\"Invoices\",\"placedWidgets\":[]}]}]}";
        l.updatedAt = Instant.parse("2026-11-24T05:00:00.123456789Z");
        when(layouts.findFirstByPortalAndStatus("merchant", UiLayout.Status.PUBLISHED)).thenReturn(Optional.of(l));
        when(widgets.findAllByOrderByIdAsc()).thenReturn(List.of(
            widget("billing.invoices", "[\"admin\",\"merchant\"]", "ACTIVE"),
            widget("admin.only", "[\"admin\"]", "ACTIVE"),
            widget("old.widget", "[\"merchant\"]", "RETIRED")));

        Map<String, Object> menu = service.menu("merchant");

        assertThat(menu).containsEntry("portal", "merchant").containsEntry("schemaVersion", 1)
            .containsEntry("layoutVersion", "00000000-0000-0000-0000-00000000aaaa")
            .containsEntry("publishedAt", "2026-11-24T05:00:00.123456Z");
        assertThat(menu.get("menus")).as("plain JSON values, not a JsonNode").isInstanceOf(List.class);
        assertThat(menu.get("menus").toString()).contains("invoices");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ws = (List<Map<String, Object>>) menu.get("widgets");
        assertThat(ws).extracting(w -> w.get("id")).containsExactly("billing.invoices");
        assertThat(ws.get(0)).containsEntry("requires", List.of("invoice.view")).containsEntry("bundleHash", "sha256:unregistered");
        assertThat(menu).doesNotContainKeys("jws", "kid", "digest");
    }

    @Test
    void aPortalWithNothingPublishedIsA404() {
        when(layouts.findFirstByPortalAndStatus("wholesaler", UiLayout.Status.PUBLISHED)).thenReturn(Optional.empty());
        ApiException e = catchThrowableOfType(() -> service.menu("wholesaler"), ApiException.class);
        assertThat(e.getStatus().value()).isEqualTo(404);
        assertThat(e.getCode()).isEqualTo("UI_MENU_NOT_FOUND");
    }
}
