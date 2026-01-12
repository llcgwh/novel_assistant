// 导出功能

// 切换导出菜单
function toggleExportMenu() {
    const menu = document.getElementById('export-menu');
    menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
}

// 点击其他地方关闭菜单
document.addEventListener('click', (e) => {
    const dropdown = document.querySelector('.export-dropdown');
    const menu = document.getElementById('export-menu');
    if (dropdown && menu && !dropdown.contains(e.target)) {
        menu.style.display = 'none';
    }
});

// 导出为JSON
async function exportToJson() {
    try {
        const response = await fetch(`${api.baseUrl}/novels/${getCurrentNovelId()}/export/json`);
        if (!response.ok) throw new Error('Export failed');

        const data = await response.json();
        downloadFile(JSON.stringify(data, null, 2), 'novel-export.json', 'application/json');

        toggleExportMenu();
    } catch (error) {
        console.error('Export to JSON failed:', error);
        alert('导出失败，请重试');
    }
}

// 导出为Markdown
async function exportToMarkdown() {
    try {
        const response = await fetch(`${api.baseUrl}/novels/${getCurrentNovelId()}/export/markdown`);
        if (!response.ok) throw new Error('Export failed');

        const text = await response.text();
        downloadFile(text, 'novel-export.md', 'text/markdown');

        toggleExportMenu();
    } catch (error) {
        console.error('Export to Markdown failed:', error);
        alert('导出失败，请重试');
    }
}

// 导出人物设定为Markdown
async function exportCharactersToMarkdown() {
    try {
        const response = await fetch(`${api.baseUrl}/novels/${getCurrentNovelId()}/export/characters/markdown`);
        if (!response.ok) throw new Error('Export failed');

        const text = await response.text();
        downloadFile(text, 'characters-export.md', 'text/markdown');

        toggleExportMenu();
    } catch (error) {
        console.error('Export characters failed:', error);
        alert('导出失败，请重试');
    }
}

// 导出大纲为Markdown
async function exportOutlinesToMarkdown() {
    try {
        const response = await fetch(`${api.baseUrl}/novels/${getCurrentNovelId()}/export/outlines/markdown`);
        if (!response.ok) throw new Error('Export failed');

        const text = await response.text();
        downloadFile(text, 'outlines-export.md', 'text/markdown');

        toggleExportMenu();
    } catch (error) {
        console.error('Export outlines failed:', error);
        alert('导出失败，请重试');
    }
}

// 下载文件
function downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}


