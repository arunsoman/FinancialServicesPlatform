package com.fsp.core.rbac;

import com.fsp.core.common.ApiException;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One cell of the Permissions Matrix: an action on a PUBLISHED submenu, on the wire as
 * {@code "<menuId>/<screenId>:<ACTION>"} (ids are the published layout's ids, so the format
 * cannot collide: they are {@code ^[a-z][a-z0-9-]{0,47}$}).
 */
public record MenuCell(String menuId, String screenId, String action) {

    /** ACTIVE = the submenu shows in the sidebar; the rest are what its screen may do. */
    public static final List<String> ACTIONS = List.of("CREATE", "VIEW", "UPDATE", "DELETE", "AUTHORIZE", "ACTIVE");

    private static final Pattern WIRE = Pattern.compile(
        "^([a-z][a-z0-9-]{0,47})/([a-z][a-z0-9-]{0,47}):(CREATE|VIEW|UPDATE|DELETE|AUTHORIZE|ACTIVE)$");

    public static MenuCell parse(String raw) {
        Matcher m = raw == null ? null : WIRE.matcher(raw.strip());
        if (m == null || !m.matches()) {
            throw ApiException.badRequest("MENU_CELL_INVALID",
                "Menu cell '%s' must look like <menu>/<submenu>:<%s>".formatted(raw, String.join("|", ACTIONS)));
        }
        return new MenuCell(m.group(1), m.group(2), m.group(3));
    }

    /** Parsed, de-duplicated and sorted wire form (null → empty). */
    public static List<String> normalize(Collection<String> raw) {
        if (raw == null) return List.of();
        Set<String> out = new TreeSet<>();
        for (String r : raw) out.add(parse(r).wire());
        return List.copyOf(out);
    }

    public String screen() {
        return menuId + "/" + screenId;
    }

    public String wire() {
        return screen() + ":" + action;
    }
}
