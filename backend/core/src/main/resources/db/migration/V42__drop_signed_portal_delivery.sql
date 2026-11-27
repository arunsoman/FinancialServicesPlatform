-- V42: signed portal delivery is removed. Portals now read their published menus straight from
-- ui_layouts (GET /v1/ui/menus/{portal}); nothing is signed, so the stored signed bundles
-- (V10) have no reader. ui_widgets.bundle_sha256 stays: the widget registry still records it.
DROP TABLE IF EXISTS ui_bundles;
