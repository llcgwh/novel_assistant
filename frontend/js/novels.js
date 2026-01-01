// 小说管理功能
let novels = [];

// 加载所有小说
async function loadNovels() {
    try {
        novels = await api.novels.getAll();
        renderNovels();
    } catch (error) {
        console.error('Failed to load novels:', error);
    }
}

// 渲染小说列表
function renderNovels() {
    const container = document.getElementById('novels-list');
    if (novels.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无小说</h3><p>点击"创建新小说"按钮开始您的创作之旅</p></div>';
        return;
    }

    container.innerHTML = novels.map(novel => `
        <div class="novel-card" data-id="${novel.id}">
            <div class="novel-cover">
                ${novel.coverImage ? `<img src="${api.images.getFileUrl(novel.coverImage)}" alt="${novel.title}">` : '<div class="no-cover">暂无封面</div>'}
            </div>
            <div class="novel-info">
                <h3>${novel.title}</h3>
                ${novel.author ? `<p class="novel-author">作者：${novel.author}</p>` : ''}
                ${novel.genre ? `<p class="novel-genre">类型：${novel.genre}</p>` : ''}
                <span class="status-badge ${novel.status}">${getNovelStatusText(novel.status)}</span>
            </div>
            <div class="novel-actions">
                <button class="btn-primary" onclick="enterNovel(${novel.id})">进入</button>
                <button class="btn-secondary" onclick="editNovel(${novel.id})">编辑</button>
                <button class="btn-danger" onclick="deleteNovel(${novel.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 获取小说状态文本
function getNovelStatusText(status) {
    const statusMap = {
        'planning': '规划中',
        'writing': '写作中',
        'completed': '已完成',
        'paused': '已暂停'
    };
    return statusMap[status] || status;
}

// 显示小说模态框
function showNovelModal(novelId = null) {
    const novel = novelId ? novels.find(n => n.id === novelId) : null;
    const isEdit = !!novel;

    createModal(isEdit ? '编辑小说' : '创建新小说', `
        <form id="novel-form">
            <div class="form-group">
                <label>小说标题 *</label>
                <input type="text" id="novel-title" value="${novel?.title || ''}" required>
            </div>
            <div class="form-group">
                <label>作者</label>
                <input type="text" id="novel-author" value="${novel?.author || ''}">
            </div>
            <div class="form-group">
                <label>类型</label>
                <input type="text" id="novel-genre" value="${novel?.genre || ''}" placeholder="例如：玄幻、都市、科幻">
            </div>
            <div class="form-group">
                <label>状态</label>
                <select id="novel-status">
                    <option value="planning" ${novel?.status === 'planning' ? 'selected' : ''}>规划中</option>
                    <option value="writing" ${novel?.status === 'writing' || !novel ? 'selected' : ''}>写作中</option>
                    <option value="completed" ${novel?.status === 'completed' ? 'selected' : ''}>已完成</option>
                    <option value="paused" ${novel?.status === 'paused' ? 'selected' : ''}>已暂停</option>
                </select>
            </div>
            <div class="form-group">
                <label>简介</label>
                <textarea id="novel-description">${novel?.description || ''}</textarea>
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('novel-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveNovel(novelId);
    });
}

// 保存小说
async function saveNovel(novelId) {
    const data = {
        title: document.getElementById('novel-title').value,
        author: document.getElementById('novel-author').value,
        genre: document.getElementById('novel-genre').value,
        status: document.getElementById('novel-status').value,
        description: document.getElementById('novel-description').value
    };

    try {
        if (novelId) {
            await api.novels.update(novelId, data);
        } else {
            await api.novels.create(data);
        }
        closeModal();
        await loadNovels();
    } catch (error) {
        console.error('Failed to save novel:', error);
        alert('保存失败，请重试');
    }
}

// 编辑小说
function editNovel(id) {
    showNovelModal(id);
}

// 删除小说
async function deleteNovel(id) {
    if (!confirm('确定要删除这部小说吗？这将删除所有相关数据！')) return;

    try {
        await api.novels.delete(id);
        await loadNovels();
    } catch (error) {
        console.error('Failed to delete novel:', error);
        alert('删除失败，请重试');
    }
}

// 进入小说
async function enterNovel(novelId) {
    setCurrentNovel(novelId);
    const novel = novels.find(n => n.id === novelId);

    document.getElementById('novel-selector').style.display = 'none';
    document.getElementById('main-app').style.display = 'block';
    document.getElementById('current-novel-title').textContent = novel.title;

    await loadAllData();
}

// 返回小说选择页面
function backToNovelSelector() {
    document.getElementById('main-app').style.display = 'none';
    document.getElementById('novel-selector').style.display = 'block';
    setCurrentNovel(null);
}
