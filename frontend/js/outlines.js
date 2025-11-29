// 大纲管理功能
let outlines = [];

// 加载所有大纲
async function loadOutlines() {
    try {
        outlines = await api.outlines.getAll();
        // 按情节顺序排序
        outlines.sort((a, b) => (a.plotOrder || 0) - (b.plotOrder || 0));
        renderOutlines();
    } catch (error) {
        console.error('Failed to load outlines:', error);
    }
}

// 渲染大纲列表
function renderOutlines() {
    const container = document.getElementById('outlines-list');
    if (outlines.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无大纲</h3><p>点击"添加大纲"按钮创建第一个大纲</p></div>';
        return;
    }

    container.innerHTML = outlines.map(outline => `
        <div class="card" data-id="${outline.id}">
            <h3>${outline.title}</h3>
            <p><span class="status-badge ${outline.status}">${getOutlineStatusText(outline.status)}</span></p>
            ${outline.chapterNumber ? `<p><span class="label">章节：</span>第 ${outline.chapterNumber} 章</p>` : ''}
            ${outline.plotOrder ? `<p><span class="label">情节顺序：</span>${outline.plotOrder}</p>` : ''}
            ${outline.content ? `<p><span class="label">内容：</span>${outline.content}</p>` : ''}
            <div class="actions">
                <button class="btn-secondary" onclick="editOutline(${outline.id})">编辑</button>
                <button class="btn-danger" onclick="deleteOutline(${outline.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 获取大纲状态文本
function getOutlineStatusText(status) {
    const statusMap = {
        'planning': '规划中',
        'writing': '写作中',
        'completed': '已完成'
    };
    return statusMap[status] || status;
}

// 显示添加大纲模态框
function showOutlineModal(outlineId = null) {
    const outline = outlineId ? outlines.find(o => o.id === outlineId) : null;
    const isEdit = !!outline;

    createModal(isEdit ? '编辑大纲' : '添加大纲', `
        <form id="outline-form">
            <div class="form-group">
                <label>标题 *</label>
                <input type="text" id="outline-title" value="${outline?.title || ''}" required>
            </div>
            <div class="form-group">
                <label>章节号</label>
                <input type="number" id="outline-chapter" value="${outline?.chapterNumber || ''}" placeholder="例如：1">
            </div>
            <div class="form-group">
                <label>情节顺序</label>
                <input type="number" id="outline-order" value="${outline?.plotOrder || ''}" placeholder="用于排序">
            </div>
            <div class="form-group">
                <label>大纲内容</label>
                <textarea id="outline-content">${outline?.content || ''}</textarea>
            </div>
            <div class="form-group">
                <label>状态</label>
                <select id="outline-status">
                    <option value="planning" ${outline?.status === 'planning' ? 'selected' : ''}>规划中</option>
                    <option value="writing" ${outline?.status === 'writing' ? 'selected' : ''}>写作中</option>
                    <option value="completed" ${outline?.status === 'completed' ? 'selected' : ''}>已完成</option>
                </select>
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('outline-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveOutline(outlineId);
    });
}

// 保存大纲
async function saveOutline(outlineId) {
    const data = {
        title: document.getElementById('outline-title').value,
        chapterNumber: parseInt(document.getElementById('outline-chapter').value) || null,
        plotOrder: parseInt(document.getElementById('outline-order').value) || null,
        content: document.getElementById('outline-content').value,
        status: document.getElementById('outline-status').value
    };

    try {
        if (outlineId) {
            await api.outlines.update(outlineId, data);
        } else {
            await api.outlines.create(data);
        }
        closeModal();
        await loadOutlines();
    } catch (error) {
        console.error('Failed to save outline:', error);
        alert('保存失败，请重试');
    }
}

// 编辑大纲
function editOutline(id) {
    showOutlineModal(id);
}

// 删除大纲
async function deleteOutline(id) {
    if (!confirm('确定要删除这个大纲吗？')) return;

    try {
        await api.outlines.delete(id);
        await loadOutlines();
    } catch (error) {
        console.error('Failed to delete outline:', error);
        alert('删除失败，请重试');
    }
}
