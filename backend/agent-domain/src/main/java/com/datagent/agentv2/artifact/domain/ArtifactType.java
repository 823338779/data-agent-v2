package com.datagent.agentv2.artifact.domain;

/** 系统允许生成和下载的 Office Open XML 产物类型。 */
public enum ArtifactType {
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    private final String mediaType;
    private final String extension;

    ArtifactType(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    /** 返回下载响应使用的固定 MIME 类型。 */
    public String mediaType() {
        return mediaType;
    }

    /** 返回不带句点的固定文件扩展名。 */
    public String extension() {
        return extension;
    }
}
