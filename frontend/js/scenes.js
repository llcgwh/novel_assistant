// 场景管理功能
let scenes = [];

// 加载所有场景
async function loadScenes() {
    try {
        scenes = await api.scenes.getAll();
        renderScenes();
    } catch (error) {
        console.error('Failed to load scenes:', error);
    }
}

// 渲染场景列表
function renderScenes() {
    const container = document.getElementById('scenes-list');
    if (!container) return;

    if (scenes.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无场景</h3><p>点击"添加场景"按钮创建第一个场景</p></div>';
        return;
    }

    container.innerHTML = scenes.map(scene => `
        <div class="card" data-id="${scene.id}">
            ${scene.sceneImage ? `<div class="card-image"><img src="${api.images.getFileUrl(scene.sceneImage)}" alt="${scene.name}"></div>` : ''}
            <h3>${scene.name}</h3>
            ${scene.location ? `<p><span class="label">位置：</span>${scene.location}</p>` : ''}
            ${scene.description ? `<p><span class="label">描述：</span>${scene.description}</p>` : ''}
            ${scene.atmosphere ? `<p><span class="label">氛围：</span>${scene.atmosphere}</p>` : ''}
            ${renderElementTags(scene.tags)}
            <div class="actions">
                <button class="btn-secondary" onclick="editScene(${scene.id})">编辑</button>
                <button class="btn-secondary" onclick="manageSceneTags(${scene.id})">标签</button>
                <button class="btn-danger" onclick="deleteScene(${scene.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 搜索场景
let sceneSearchTimeout;
async function searchScenes(keyword) {
    clearTimeout(sceneSearchTimeout);
    sceneSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                scenes = await api.scenes.search(keyword);
                renderScenes();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadScenes();
        }
    }, 300);
}

// 显示添加场景模态框
function showSceneModal(sceneId = null) {
    const scene = sceneId ? scenes.find(s => s.id === sceneId) : null;
    const isEdit = !!scene;

    createModal(isEdit ? '编辑场景' : '添加场景', `
        <form id="scene-form">
            <div class="form-group">
                <label>场景名称 *</label>
                <input type="text" id="scene-name" value="${scene?.name || ''}" required>
            </div>
            <div class="form-group">
                <label>位置</label>
                <input type="text" id="scene-location" value="${scene?.location || ''}" placeholder="例如：王宫大殿、森林深处">
            </div>
            <div class="form-group">
                <label>场景描述</label>
                <textarea id="scene-description">${scene?.description || ''}</textarea>
            </div>
            <div class="form-group">
                <label>氛围</label>
                <textarea id="scene-atmosphere" placeholder="描述场景的氛围、感觉">${scene?.atmosphere || ''}</textarea>
            </div>
            <div class="form-group">
                <label>场景图片</label>
                <input type="file" id="scene-image" accept="image/*">
                ${scene?.sceneImage ? '<p class="current-image">已有场景图片</p>' : ''}
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('scene-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveScene(sceneId);
    });
}

// 保存场景
async function saveScene(sceneId) {
    const fileInput = document.getElementById('scene-image');
    let sceneImage = sceneId ? scenes.find(s => s.id === sceneId)?.sceneImage : null;

    if (fileInput.files.length > 0) {
        try {
            const uploadedImage = await uploadImage(fileInput.files[0], 'scene');
            sceneImage = uploadedImage.id;
        } catch (error) {
            console.error('Failed to upload image:', error);
        }
    }

    const data = {
        name: document.getElementById('scene-name').value,
        location: document.getElementById('scene-location').value,
        description: document.getElementById('scene-description').value,
        atmosphere: document.getElementById('scene-atmosphere').value,
        sceneImage: sceneImage
    };

    try {
        if (sceneId) {
            await api.scenes.update(sceneId, data);
        } else {
            await api.scenes.create(data);
        }
        closeModal();
        await loadScenes();
    } catch (error) {
        console.error('Failed to save scene:', error);
        alert('保存失败，请重试');
    }
}

// 编辑场景
function editScene(id) {
    showSceneModal(id);
}

// 删除场景
async function deleteScene(id) {
    if (!confirm('确定要删除这个场景吗？')) return;

    try {
        await api.scenes.delete(id);
        await loadScenes();
    } catch (error) {
        console.error('Failed to delete scene:', error);
        alert('删除失败，请重试');
    }
}

// 管理场景标签
function manageSceneTags(sceneId) {
    const scene = scenes.find(s => s.id === sceneId);
    const selectedTagIds = scene.tags ? scene.tags.map(t => t.id) : [];

    createModal('管理标签 - ' + scene.name, `
        <form id="scene-tags-form">
            ${generateTagSelector(selectedTagIds)}
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存</button>
            </div>
        </form>
    `);

    document.getElementById('scene-tags-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tagIds = getSelectedTagIds('scene-tags-form');
        try {
            await api.scenes.setTags(sceneId, tagIds);
            closeModal();
            await loadScenes();
        } catch (error) {
            console.error('Failed to update tags:', error);
            alert('保存失败，请重试');
        }
    });
}
