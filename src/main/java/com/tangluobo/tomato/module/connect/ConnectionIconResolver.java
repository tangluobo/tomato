package com.tangluobo.tomato.module.connect;

/** Resolves the most specific icon for a saved connection or tool entry. */
public final class ConnectionIconResolver {
    public static final String FOLDER_ICON = "/images/connect/folder.png";

    private ConnectionIconResolver() {
    }

    public static String resolve(ConnectionConfig config) {
        if (config == null || config.getType() == null) return FOLDER_ICON;

        if (config.getType() == ConnectType.TOOL) {
            ToolType toolType = ToolType.fromCode(config.getToolType());
            return toolType != null ? toolType.getIconPath() : ConnectType.TOOL.getIconPath();
        }

        DatabaseProfile profile = DatabaseProfileRegistry.resolve(config);
        return profile != null ? profile.iconPath() : config.getType().getIconPath();
    }
}
