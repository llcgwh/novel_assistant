// 伏笔管理功能
let foreshadows = [];
let currentForeshadowFilter = '';

// 加载所有伏笔
async function loadForeshadows() {
    try {
        foreshadows = await api.foreshadows.getAll();
        renderForeshadows();
    } catch (error) {
        console.error('Failed to load foreshadows:', error);
    }
}

// 渲染伏笔列表
function renderForeshadows() {
    const container = document.getElementById('foreshadows-list');
    if (!container) return;

    let filteredForeshadows = foreshadows;
    if (currentForeshadowFilter) {
        filteredForeshadows = foreshadows.filter(f => f.status === currentForeshadowFilter);
    }

    if (filteredForeshadows.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无伏笔</h3><p>点击"添加伏笔"按钮创建第一个伏笔</p></div>';
        return;
    }

    container.innerHTML = filteredForeshadows.map(foreshadow => `
        <div class="card" data-id="${foreshadow.id}">
            <h3>${foreshadow.title}</h3>
            <p><span class="status-badge ${foreshadow.status}">${getStatusText(foreshadow.status)}</span></p>
            ${foreshadow.content ? `<p><span class="label">内容：</span>${foreshadow.content}</p>` : ''}
            ${foreshadow.laidAt ? `<p><span class="label">埋下位置：</span>${foreshadow.laidAt}</p>` : ''}
            ${foreshadow.revealedAt ? `<p><span class="label">揭示位置：</span>${foreshadow.revealedAt}</p>` : ''}
            ${renderElementTags(foreshadow.tags)}
            <div class="actions">
                <button class="btn-secondary" onclick="editForeshadow(${foreshadow.id})">编辑</button>
                <button class="btn-secondary" onclick="manageForeshadowTags(${foreshadow.id})">标签</button>
                <button class="btn-danger" onclick="deleteForeshadow(${foreshadow.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 获取状态文本
function getStatusText(status) {
    const statusMap = {
        'pending': '未揭示',
        'revealed': '已揭示',
        'abandoned': '已废弃'
    };
    return statusMap[status] || status;
}

// 搜索伏笔
let foreshadowSearchTimeout;
async function searchForeshadows(keyword) {
    clearTimeout(foreshadowSearchTimeout);
    foreshadowSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                foreshadows = await api.foreshadows.search(keyword);
                renderForeshadows();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadForeshadows();
        }
    }, 300);
}

// 按状态筛选伏笔
function filterForeshadowsByStatus(status) {
    currentForeshadowFilter = status;
    renderForeshadows();
}

// 显示添加伏笔模态框
function showForeshadowModal(foreshadowId = null) {
    const foreshadow = foreshadowId ? foreshadows.find(f => f.id === foreshadowId) : null;
    const isEdit = !!foreshadow;

    createModal(isEdit ? '编辑伏笔' : '添加伏笔', `
        <form id="foreshadow-form">
            <div class="form-group">
                <label>标题 *</label>
                <input type="text" id="foreshadow-title" value="${foreshadow?.title || ''}" required>
            </div>
            <div class="form-group">
                <label>伏笔内容</label>
                <textarea id="foreshadow-content">${foreshadow?.content || ''}</textarea>
            </div>
            <div class="form-group">
                <label>埋下位置</label>
                <input type="text" id="foreshadow-laid-at" value="${foreshadow?.laidAt || ''}" placeholder="例如：第3章">
            </div>
            <div class="form-group">
                <label>揭示位置</label>
                <input type="text" id="foreshadow-revealed-at" value="${foreshadow?.revealedAt || ''}" placeholder="例如：第15章">
            </div>
            <div class="form-group">
                <label>状态</label>
                <select id="foreshadow-status">
                    <option value="pending" ${foreshadow?.status === 'pending' || !foreshadow ? 'selected' : ''}>未揭示</option>
                    <option value="revealed" ${foreshadow?.status === 'revealed' ? 'selected' : ''}>已揭示</option>
                    <option value="abandoned" ${foreshadow?.status === 'abandoned' ? 'selected' : ''}>已废弃</option>
                </select>
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('foreshadow-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveForeshadow(foreshadowId);
    });
}

// 保存伏笔
async function saveForeshadow(foreshadowId) {
    const data = {
        title: document.getElementById('foreshadow-title').value,
        content: document.getElementById('foreshadow-content').value,
        laidAt: document.getElementById('foreshadow-laid-at').value,
        revealedAt: document.getElementById('foreshadow-revealed-at').value,
        status: document.getElementById('foreshadow-status').value
    };

    try {
        if (foreshadowId) {
            await api.foreshadows.update(foreshadowId, data);
        } else {
            await api.foreshadows.create(data);
        }
        closeModal();
        await loadForeshadows();
    } catch (error) {
        console.error('Failed to save foreshadow:', error);
        alert('保存失败，请重试');
    }
}

// 编辑伏笔
function editForeshadow(id) {
    showForeshadowModal(id);
}

// 删除伏笔
async function deleteForeshadow(id) {
    if (!confirm('确定要删除这个伏笔吗？')) return;

    try {
        await api.foreshadows.delete(id);
        await loadForeshadows();
    } catch (error) {
        console.error('Failed to delete foreshadow:', error);
        alert('删除失败，请重试');
    }
}

// 管理伏笔标签
function manageForeshadowTags(foreshadowId) {
    const foreshadow = foreshadows.find(f => f.id === foreshadowId);
    const selectedTagIds = foreshadow.tags ? foreshadow.tags.map(t => t.id) : [];

    createModal('管理标签 - ' + foreshadow.title, `
        <form id="foreshadow-tags-form">
            ${generateTagSelector(selectedTagIds)}
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存</button>
            </div>
        </form>
    `);

    document.getElementById('foreshadow-tags-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tagIds = getSelectedTagIds('foreshadow-tags-form');
        try {
            await api.foreshadows.setTags(foreshadowId, tagIds);
            closeModal();
            await loadForeshadows();
        } catch (error) {
            console.error('Failed to update tags:', error);
            alert('保存失败，请重试');
        }
    });
}
