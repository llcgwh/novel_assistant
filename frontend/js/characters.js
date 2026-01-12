// 人物管理功能
let characters = [];

// 加载所有人物
async function loadCharacters() {
    try {
        characters = await api.characters.getAll();
        renderCharacters();
    } catch (error) {
        console.error('Failed to load characters:', error);
    }
}

// 渲染人物列表
function renderCharacters() {
    const container = document.getElementById('characters-list');
    if (characters.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无人物</h3><p>点击"添加人物"按钮创建第一个人物</p></div>';
        return;
    }

    container.innerHTML = characters.map(char => `
        <div class="card" data-id="${char.id}">
            ${char.portraitImage ? `<div class="card-image"><img src="${api.images.getFileUrl(char.portraitImage)}" alt="${char.name}"></div>` : ''}
            <h3>${char.name}</h3>
            ${char.role ? `<p><span class="label">角色定位：</span>${char.role}</p>` : ''}
            ${char.description ? `<p><span class="label">描述：</span>${char.description}</p>` : ''}
            ${char.personality ? `<p><span class="label">性格：</span>${char.personality}</p>` : ''}
            ${char.appearance ? `<p><span class="label">外貌：</span>${char.appearance}</p>` : ''}
            ${char.background ? `<p><span class="label">背景：</span>${char.background}</p>` : ''}
            ${renderElementTags(char.tags)}
            <div class="actions">
                <button class="btn-secondary" onclick="editCharacter(${char.id})">编辑</button>
                <button class="btn-secondary" onclick="manageCharacterTags(${char.id})">标签</button>
                <button class="btn-danger" onclick="deleteCharacter(${char.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 搜索人物
let characterSearchTimeout;
async function searchCharacters(keyword) {
    clearTimeout(characterSearchTimeout);
    characterSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                characters = await api.characters.search(keyword);
                renderCharacters();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadCharacters();
        }
    }, 300);
}

// 显示添加人物模态框
function showCharacterModal(characterId = null) {
    const character = characterId ? characters.find(c => c.id === characterId) : null;
    const isEdit = !!character;
    const selectedTagIds = character?.tags ? character.tags.map(t => t.id) : [];

    createModal(isEdit ? '编辑人物' : '添加人物', `
        <form id="character-form">
            <div class="form-group">
                <label>姓名 *</label>
                <input type="text" id="char-name" value="${character?.name || ''}" required>
            </div>
            <div class="form-group">
                <label>角色定位</label>
                <input type="text" id="char-role" value="${character?.role || ''}" placeholder="例如：主角、配角、反派">
            </div>
            <div class="form-group">
                <label>描述</label>
                <textarea id="char-description">${character?.description || ''}</textarea>
            </div>
            <div class="form-group">
                <label>性格</label>
                <textarea id="char-personality">${character?.personality || ''}</textarea>
            </div>
            <div class="form-group">
                <label>外貌</label>
                <textarea id="char-appearance">${character?.appearance || ''}</textarea>
            </div>
            <div class="form-group">
                <label>背景故事</label>
                <textarea id="char-background">${character?.background || ''}</textarea>
            </div>
            <div class="form-group">
                <label>人物肖像</label>
                <input type="file" id="char-portrait" accept="image/*">
                ${character?.portraitImage ? '<p class="current-image">已有肖像图片</p>' : ''}
            </div>
            <div class="form-group">
                <label>标签</label>
                ${generateTagSelector(selectedTagIds, 'character-form')}
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('character-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveCharacter(characterId);
    });
}

// 保存人物
async function saveCharacter(characterId) {
    const fileInput = document.getElementById('char-portrait');
    let portraitImage = characterId ? characters.find(c => c.id === characterId)?.portraitImage : null;

    if (fileInput.files.length > 0) {
        try {
            const uploadedImage = await uploadImage(fileInput.files[0], 'portrait');
            portraitImage = uploadedImage.id;
        } catch (error) {
            console.error('Failed to upload image:', error);
        }
    }

    const data = {
        name: document.getElementById('char-name').value,
        role: document.getElementById('char-role').value,
        description: document.getElementById('char-description').value,
        personality: document.getElementById('char-personality').value,
        appearance: document.getElementById('char-appearance').value,
        background: document.getElementById('char-background').value,
        portraitImage: portraitImage
    };

    try {
        let savedCharacter;
        if (characterId) {
            savedCharacter = await api.characters.update(characterId, data);
        } else {
            savedCharacter = await api.characters.create(data);
            characterId = savedCharacter.id;
        }

        // 保存标签
        const tagIds = getSelectedTagIds('character-form');
        await api.characters.setTags(characterId, tagIds);

        closeModal();
        await loadCharacters();
    } catch (error) {
        console.error('Failed to save character:', error);
        alert('保存失败，请重试');
    }
}

// 编辑人物
function editCharacter(id) {
    showCharacterModal(id);
}

// 删除人物
async function deleteCharacter(id) {
    if (!confirm('确定要删除这个人物吗？')) return;

    try {
        await api.characters.delete(id);
        await loadCharacters();
    } catch (error) {
        console.error('Failed to delete character:', error);
        alert('删除失败，请重试');
    }
}

// 管理人物标签
function manageCharacterTags(characterId) {
    const character = characters.find(c => c.id === characterId);
    const selectedTagIds = character.tags ? character.tags.map(t => t.id) : [];

    createModal('管理标签 - ' + character.name, `
        <form id="character-tags-form">
            ${generateTagSelector(selectedTagIds)}
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存</button>
            </div>
        </form>
    `);

    document.getElementById('character-tags-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tagIds = getSelectedTagIds('character-tags-form');
        try {
            await api.characters.setTags(characterId, tagIds);
            closeModal();
            await loadCharacters();
        } catch (error) {
            console.error('Failed to update tags:', error);
            alert('保存失败，请重试');
        }
    });
}
