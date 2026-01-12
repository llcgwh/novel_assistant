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

// 生成标签选择器HTML（支持内联创建新标签）
function generateTagSelector(selectedTagIds = [], formId = '') {
    const selectorId = formId ? `tag-selector-${formId}` : 'tag-selector';

    return `
        <div class="tag-selector-container" id="${selectorId}">
            <div class="tag-input-wrapper">
                <input type="text" class="tag-input" placeholder="输入标签名称，按回车创建" onkeydown="handleTagInput(event, '${selectorId}')">
                <button type="button" class="btn-small btn-add-tag" onclick="addNewTagFromInput('${selectorId}')">添加</button>
            </div>
            <div class="tag-selector">
                ${tags.map(tag => `
                    <label class="tag-checkbox" style="border-color: ${tag.color}" data-tag-id="${tag.id}">
                        <input type="checkbox" value="${tag.id}" ${selectedTagIds.includes(tag.id) ? 'checked' : ''}>
                        <span class="tag-label" style="background-color: ${tag.color}">${tag.name}</span>
                    </label>
                `).join('')}
            </div>
            <div class="new-tags-container"></div>
        </div>
    `;
}

// 处理标签输入框的键盘事件
function handleTagInput(event, selectorId) {
    if (event.key === 'Enter') {
        event.preventDefault();
        addNewTagFromInput(selectorId);
    }
}

// 从输入框添加新标签
async function addNewTagFromInput(selectorId) {
    const container = document.getElementById(selectorId);
    const input = container.querySelector('.tag-input');
    const tagName = input.value.trim();

    if (!tagName) return;

    // 检查标签是否已存在
    const existingTag = tags.find(t => t.name.toLowerCase() === tagName.toLowerCase());
    if (existingTag) {
        // 如果标签已存在，直接选中它
        const checkbox = container.querySelector(`input[value="${existingTag.id}"]`);
        if (checkbox) {
            checkbox.checked = true;
        }
        input.value = '';
        return;
    }

    // 创建新标签
    try {
        const randomColor = getRandomTagColor();
        const newTag = await api.tags.create({ name: tagName, color: randomColor });

        // 添加到全局标签列表
        tags.push(newTag);

        // 在选择器中添加新标签并选中
        const tagSelector = container.querySelector('.tag-selector');
        const newTagHtml = `
            <label class="tag-checkbox" style="border-color: ${newTag.color}" data-tag-id="${newTag.id}">
                <input type="checkbox" value="${newTag.id}" checked>
                <span class="tag-label" style="background-color: ${newTag.color}">${newTag.name}</span>
            </label>
        `;
        tagSelector.insertAdjacentHTML('beforeend', newTagHtml);

        input.value = '';
    } catch (error) {
        console.error('Failed to create tag:', error);
        alert('创建标签失败，请重试');
    }
}

// 生成随机标签颜色
function getRandomTagColor() {
    const colors = [
        '#3498db', '#e74c3c', '#2ecc71', '#f39c12', '#9b59b6',
        '#1abc9c', '#e67e22', '#34495e', '#16a085', '#c0392b',
        '#27ae60', '#8e44ad', '#2980b9', '#d35400', '#7f8c8d'
    ];
    return colors[Math.floor(Math.random() * colors.length)];
}

// 获取选中的标签ID
function getSelectedTagIds(formId) {
    const form = document.getElementById(formId);
    const selectorContainer = form.querySelector('.tag-selector-container') || form;
    const checkboxes = selectorContainer.querySelectorAll('.tag-selector input[type="checkbox"]:checked');
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
