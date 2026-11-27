package com.fsp.core.rbac;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** {@code role_menu_grants}: the Permissions Matrix cells a role holds, per portal. */
@Repository
public class RoleMenuGrantRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RoleMenuGrantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The role's cells, wire form, sorted. */
    public List<String> cellsForRole(UUID roleId) {
        return jdbc.queryForList("""
            SELECT menu_id || '/' || screen_id || ':' || action FROM role_menu_grants
            WHERE role_id = :roleId ORDER BY 1
            """, new MapSqlParameterSource("roleId", roleId), String.class);
    }

    /** The portal a role's cells are keyed to (null = the role holds no cells). */
    public String portalOfRole(UUID roleId) {
        List<String> rows = jdbc.queryForList(
            "SELECT DISTINCT portal FROM role_menu_grants WHERE role_id = :roleId ORDER BY portal LIMIT 1",
            new MapSqlParameterSource("roleId", roleId), String.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Union of the cells these roles hold for one portal. */
    public Set<String> cellsForRoles(Collection<UUID> roleIds, String portal) {
        if (roleIds.isEmpty()) return Set.of();
        return new TreeSet<>(jdbc.queryForList("""
            SELECT DISTINCT menu_id || '/' || screen_id || ':' || action FROM role_menu_grants
            WHERE role_id IN (:roleIds) AND portal = :portal
            """, new MapSqlParameterSource("roleIds", roleIds).addValue("portal", portal), String.class));
    }

    /**
     * Replace the role's cells with {@code proposed} (keyed to {@code portal}). Deletes then inserts
     * in sorted order — deterministic row order, so concurrent writers cannot deadlock (AGENTS §2d);
     * callers hold the role's version pin in the same transaction.
     */
    public void replace(UUID roleId, String portal, Collection<String> proposed) {
        jdbc.update("DELETE FROM role_menu_grants WHERE role_id = :roleId",
            new MapSqlParameterSource("roleId", roleId));
        for (String wire : new TreeSet<>(proposed)) {
            MenuCell c = MenuCell.parse(wire);
            jdbc.update("""
                INSERT INTO role_menu_grants (role_id, portal, menu_id, screen_id, action)
                VALUES (:roleId, :portal, :menuId, :screenId, :action)
                """, new MapSqlParameterSource("roleId", roleId).addValue("portal", portal)
                .addValue("menuId", c.menuId()).addValue("screenId", c.screenId()).addValue("action", c.action()));
        }
    }
}
