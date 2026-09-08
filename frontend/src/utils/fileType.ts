/** 按文件名判定二进制预览格式（RightPreview 附件 / FileViewModal 项目文件共用） */
export const isDocxName = (name: string) => /\.docx$/i.test(name)
export const isXlsxName = (name: string) => /\.xlsx$/i.test(name)
