// 主应用逻辑
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    loadAllData();
});

// 初始化导航
function initNavigation() {
    const navButtons = document.querySelectorAll('.nav-btn');
    navButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const targetView = btn.dataset.view;
            switchView(targetView);
        });
    });
}

// 切换视图
function switchView(viewName) {
    // 更新导航按钮状态
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.remove('active');
        if (btn.dataset.view === viewName) {
            btn.classList.add('active');
        }
    });

    // 更新视图显示
    document.querySelectorAll('.view').forEach(view => {
        view.classList.remove('active');
    });
    document.getElementById(`${viewName}-view`).classList.add('active');
}

// 加载所有数据
async function loadAllData() {
    try {
        await Promise.all([
            loadCharacters(),
            loadScenes(),
            loadForeshadows(),
            loadOutlines(),
            loadTimelineEvents(),
            loadMapLocations()
        ]);
    } catch (error) {
        console.error('Failed to load data:', error);
    }
}

// 创建模态框
function createModal(title, content) {
    const modalContainer = document.getElementById('modal-container');
    modalContainer.innerHTML = `
        <div class="modal active">
            <div class="modal-content">
                <h2>${title}</h2>
                ${content}
            </div>
        </div>
    `;

    // 点击模态框外部关闭
    const modal = modalContainer.querySelector('.modal');
    modal.addEventListener('click', (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });
}

// 关闭模态框
function closeModal() {
    const modalContainer = document.getElementById('modal-container');
    modalContainer.innerHTML = '';
}

// 显示详情模态框
function showDetailModal(title, content) {
    createModal(title, `
        <div>${content}</div>
        <div class="modal-actions">
            <button class="btn-secondary" onclick="closeModal()">关闭</button>
        </div>
    `);
}

// 跳转到指定项
function navigateTo(type, id) {
    const viewMap = {
        'character': 'characters',
        'scene': 'scenes',
        'foreshadow': 'foreshadows',
        'outline': 'outlines'
    };

    const view = viewMap[type];
    if (view) {
        switchView(view);
        // 高亮显示目标项
        setTimeout(() => {
            const element = document.querySelector(`[data-id="${id}"]`);
            if (element) {
                element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                element.style.backgroundColor = '#fff3cd';
                setTimeout(() => {
                    element.style.backgroundColor = '';
                }, 2000);
            }
        }, 100);
    }
}
