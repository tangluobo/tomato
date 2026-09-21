package com.tangluobo.tomato.module.connect;

/**
 * 工具类型枚举：与 ToolsModule 中的工具列表保持一致。
 * 工具不同于连接，无需配置主机/端口等信息，选择即添加，
 * 双击工具节点时打开与「工具」导航模块相同的界面。
 */
public enum ToolType {
    IMAGE_FORMAT_CONVERTER("image_format_converter", "图片格式转换", "/images/connect/tool-icons/image-converter.svg"),
    DATASET_CONVERTER("dataset_converter", "数据集格式转换", "/images/connect/tool-icons/dataset-converter.svg"),
    JSON_TOOL("json_tool", "JSON处理工具", "/images/connect/tool-icons/json-tool.svg"),
    DESKTOP_SHORTCUT("desktop_shortcut", "桌面快捷方式", "/images/connect/tool-icons/desktop-shortcut.svg"),
    HOSTS_FILE("hosts_file", "Hosts文件管理", "/images/connect/tool-icons/hosts-file.svg"),
    IMAGE_BACKGROUND_REMOVER("image_background_remover", "图标制作工具", "/images/connect/tool-icons/icon-maker.svg"),
    TRAE_SESSION("trae_session", "Trae会话管理", "/images/connect/tool-icons/session.svg"),
    RESOURCE_EXTRACTOR("resource_extractor", "资源图标提取", "/images/connect/tool-icons/resource-extractor.svg"),
    PORT_MAPPING("port_mapping", "端口映射", "/images/connect/tool-icons/port-mapping.svg"),
    DOWNLOADER("downloader", "下载器", "/images/connect/tool-icons/downloader.svg"),
    COLOR_TRANSPOSE_GAME("color_transpose_game", "颜色转置", "/images/connect/tool-icons/color-transpose.svg");

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
