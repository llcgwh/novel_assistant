// 标签管理功能
let tags = [];

// 加载所有标签
async function loadTags() {
    try {
        tags = await api.tags.getAll();
        renderTags();
    } catch (error) {
        console.error('Failed to load tags:', error);
    }
}

// 渲染标签列表
function renderTags() {
    const container = document.getElementById('tags-list');
    if (!container) return;

    if (tags.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无标签</h3><p>点击"添加标签"按钮创建标签</p></div>';
        return;
    }

    container.innerHTML = tags.map(tag => `
        <div class="tag-card" data-id="${tag.id}">
            <div class="tag-color" style="background-color: ${tag.color}"></div>
            <span class="tag-name">${tag.name}</span>
            <div class="tag-actions">
                <button class="btn-small" onclick="editTag(${tag.id})">编辑</button>
                <button class="btn-small btn-danger" onclick="deleteTag(${tag.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 搜索标签
let tagSearchTimeout;
async function searchTags(keyword) {
    clearTimeout(tagSearchTimeout);
    tagSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                const results = await api.tags.search(keyword);
                tags = results;
                renderTags();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadTags();
        }
    }, 300);
}

// 显示标签模态框
function showTagModal(tagId = null) {
    const tag = tagId ? tags.find(t => t.id === tagId) : null;
    const isEdit = !!tag;

    createModal(isEdit ? '编辑标签' : '添加标签', `
        <form id="tag-form">
            <div class="form-group">
                <label>标签名称 *</label>
                <input type="text" id="tag-name" value="${tag?.name || ''}" required>
            </div>
            <div class="form-group">
                <label>标签颜色</label>
                <input type="color" id="tag-color" value="${tag?.color || '#3498db'}">
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('tag-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveTag(tagId);
    });
}

// 保存标签
async function saveTag(tagId) {
    const data = {
        name: document.getElementById('tag-name').value,
        color: document.getElementById('tag-color').value
    };

    try {
        if (tagId) {
            await api.tags.update(tagId, data);
        } else {
            await api.tags.create(data);
        }
        closeModal();
        await loadTags();
    } catch (error) {
        console.error('Failed to save tag:', error);
        alert('保存失败，请重试');
    }
}

// 编辑标签
function editTag(id) {
    showTagModal(id);
}

// 删除标签
async function deleteTag(id) {
    if (!confirm('确定要删除这个标签吗？')) return;

    try {
        await api.tags.delete(id);
        await loadTags();
    } catch (error) {
        console.error('Failed to delete tag:', error);
        alert('删除失败，请重试');
    }
}

// 生成标签选择器HTML
function generateTagSelector(selectedTagIds = []) {
    if (tags.length === 0) {
        return '<p class="no-tags">暂无标签，请先在标签管理中创建</p>';
    }

    return `
        <div class="tag-selector">
            ${tags.map(tag => `
                <label class="tag-checkbox" style="border-color: ${tag.color}">
                    <input type="checkbox" value="${tag.id}" ${selectedTagIds.includes(tag.id) ? 'checked' : ''}>
                    <span class="tag-label" style="background-color: ${tag.color}">${tag.name}</span>
                </label>
            `).join('')}
        </div>
    `;
}

// 获取选中的标签ID
function getSelectedTagIds(formId) {
    const form = document.getElementById(formId);
    const checkboxes = form.querySelectorAll('.tag-selector input[type="checkbox"]:checked');
    return Array.from(checkboxes).map(cb => parseInt(cb.value));
}

// 渲染元素的标签
function renderElementTags(elementTags) {
    if (!elementTags || elementTags.length === 0) return '';

    return `
        <div class="element-tags">
            ${elementTags.map(tag => `
                <span class="element-tag" style="background-color: ${tag.color}">${tag.name}</span>
            `).join('')}
        </div>
    `;
}
