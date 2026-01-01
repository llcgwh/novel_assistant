// 全局搜索功能

// 处理全局搜索输入
function handleGlobalSearch(event) {
    if (event.key === 'Enter') {
        performGlobalSearch();
    }
}

// 执行全局搜索
async function performGlobalSearch() {
    const keyword = document.getElementById('global-search-input').value.trim();
    if (!keyword) {
        alert('请输入搜索关键词');
        return;
    }

    try {
        const results = await api.search.global(keyword);
        displaySearchResults(results, keyword);
    } catch (error) {
        console.error('Global search failed:', error);
        alert('搜索失败，请重试');
    }
}

// 显示搜索结果
function displaySearchResults(results, keyword) {
    // 切换到搜索结果视图
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.getElementById('search-results-view').classList.add('active');
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));

    const container = document.getElementById('search-results');

    const totalCount = (results.characters?.length || 0) +
                       (results.scenes?.length || 0) +
                       (results.foreshadows?.length || 0) +
                       (results.outlines?.length || 0) +
                       (results.timelineEvents?.length || 0) +
                       (results.mapLocations?.length || 0);

    if (totalCount === 0) {
        container.innerHTML = `<div class="empty-state"><h3>未找到结果</h3><p>没有找到与"${keyword}"相关的内容</p></div>`;
        return;
    }

    let html = `<p class="search-summary">找到 ${totalCount} 个与"${keyword}"相关的结果</p>`;

    // 人物结果
    if (results.characters && results.characters.length > 0) {
        html += `
            <div class="search-category">
                <h3>人物 (${results.characters.length})</h3>
                <div class="search-items">
                    ${results.characters.map(char => `
                        <div class="search-item" onclick="navigateToItem('characters', ${char.id})">
                            <h4>${highlightKeyword(char.name, keyword)}</h4>
                            ${char.role ? `<p>角色：${highlightKeyword(char.role, keyword)}</p>` : ''}
                            ${char.description ? `<p>${highlightKeyword(truncateText(char.description, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    // 场景结果
    if (results.scenes && results.scenes.length > 0) {
        html += `
            <div class="search-category">
                <h3>场景 (${results.scenes.length})</h3>
                <div class="search-items">
                    ${results.scenes.map(scene => `
                        <div class="search-item" onclick="navigateToItem('scenes', ${scene.id})">
                            <h4>${highlightKeyword(scene.name, keyword)}</h4>
                            ${scene.location ? `<p>位置：${highlightKeyword(scene.location, keyword)}</p>` : ''}
                            ${scene.description ? `<p>${highlightKeyword(truncateText(scene.description, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    // 伏笔结果
    if (results.foreshadows && results.foreshadows.length > 0) {
        html += `
            <div class="search-category">
                <h3>伏笔 (${results.foreshadows.length})</h3>
                <div class="search-items">
                    ${results.foreshadows.map(foreshadow => `
                        <div class="search-item" onclick="navigateToItem('foreshadows', ${foreshadow.id})">
                            <h4>${highlightKeyword(foreshadow.title, keyword)}</h4>
                            <span class="status-badge ${foreshadow.status}">${getStatusText(foreshadow.status)}</span>
                            ${foreshadow.content ? `<p>${highlightKeyword(truncateText(foreshadow.content, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    // 大纲结果
    if (results.outlines && results.outlines.length > 0) {
        html += `
            <div class="search-category">
                <h3>大纲 (${results.outlines.length})</h3>
                <div class="search-items">
                    ${results.outlines.map(outline => `
                        <div class="search-item" onclick="navigateToItem('outlines', ${outline.id})">
                            <h4>${highlightKeyword(outline.title, keyword)}</h4>
                            <span class="status-badge ${outline.status}">${getOutlineStatusText(outline.status)}</span>
                            ${outline.content ? `<p>${highlightKeyword(truncateText(outline.content, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    // 时间轴事件结果
    if (results.timelineEvents && results.timelineEvents.length > 0) {
        html += `
            <div class="search-category">
                <h3>时间轴事件 (${results.timelineEvents.length})</h3>
                <div class="search-items">
                    ${results.timelineEvents.map(event => `
                        <div class="search-item" onclick="navigateToItem('timeline', ${event.id})">
                            <h4>${highlightKeyword(event.title, keyword)}</h4>
                            ${event.eventTime ? `<p>时间：${highlightKeyword(event.eventTime, keyword)}</p>` : ''}
                            ${event.description ? `<p>${highlightKeyword(truncateText(event.description, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    // 地图位置结果
    if (results.mapLocations && results.mapLocations.length > 0) {
        html += `
            <div class="search-category">
                <h3>地图位置 (${results.mapLocations.length})</h3>
                <div class="search-items">
                    ${results.mapLocations.map(location => `
                        <div class="search-item" onclick="navigateToItem('map', ${location.id})">
                            <h4>${highlightKeyword(location.name, keyword)}</h4>
                            ${location.locationType ? `<p>类型：${highlightKeyword(location.locationType, keyword)}</p>` : ''}
                            ${location.description ? `<p>${highlightKeyword(truncateText(location.description, 100), keyword)}</p>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    container.innerHTML = html;
}

// 高亮关键词
function highlightKeyword(text, keyword) {
    if (!text || !keyword) return text;
    const regex = new RegExp(`(${escapeRegExp(keyword)})`, 'gi');
    return text.replace(regex, '<mark>$1</mark>');
}

// 转义正则表达式特殊字符
function escapeRegExp(string) {
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// 截断文本
function truncateText(text, maxLength) {
    if (!text || text.length <= maxLength) return text;
    return text.substring(0, maxLength) + '...';
}

// 导航到搜索结果项
function navigateToItem(viewName, itemId) {
    // 切换到对应视图
    switchView(viewName);

    // 滚动到对应项（如果可见）
    setTimeout(() => {
        const item = document.querySelector(`[data-id="${itemId}"]`);
        if (item) {
            item.scrollIntoView({ behavior: 'smooth', block: 'center' });
            item.classList.add('highlight');
            setTimeout(() => item.classList.remove('highlight'), 2000);
        }
    }, 300);
}

// 关闭搜索结果
function closeSearchResults() {
    document.getElementById('global-search-input').value = '';
    switchView('timeline');
}

// 导航到指定元素（从时间轴关联点击）
function navigateTo(type, id) {
    const viewMap = {
        'character': 'characters',
        'scene': 'scenes',
        'foreshadow': 'foreshadows',
        'outline': 'outlines'
    };

    const viewName = viewMap[type] || type;
    navigateToItem(viewName, id);
}
