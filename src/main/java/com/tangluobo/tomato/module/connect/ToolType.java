package com.tangluobo.tomato.module.connect;

/**
 * 工具类型枚举：与 ToolsModule 中的工具列表保持一致。
 * 工具不同于连接，无需配置主机/端口等信息，选择即添加，
 * 双击工具节点时打开与「工具」导航模块相同的界面。
 */
public enum ToolType {
    IMAGE_FORMAT_CONVERTER("image_format_converter", "图片格式转换", "/images/connect/beautiful.png"),
    DATASET_CONVERTER("dataset_converter", "数据集格式转换", "/images/connect/table.png"),
    JSON_TOOL("json_tool", "JSON处理工具", "/images/connect/code.png"),
    DESKTOP_SHORTCUT("desktop_shortcut", "桌面快捷方式", "/images/connect/execute.png"),
    HOSTS_FILE("hosts_file", "Hosts文件管理", "/images/connect/server.png");

    private final String code;
    private final String displayName;
    private final String iconPath;

    ToolType(String code, String displayName, String iconPath) {
        this.code = code;
        this.displayName = displayName;
        this.iconPath = iconPath;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIconPath() {
        return iconPath;
    }

    public static ToolType fromCode(String code) {
        if (code == null) return null;
        for (ToolType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
