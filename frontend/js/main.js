// 主应用逻辑
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

// 初始化应用
async function initApp() {
    initNavigation();

    // 清除之前保存的小说ID，始终显示小说列表页面
    localStorage.removeItem('currentNovelId');
    currentNovelId = null;

    // 显示小说选择页面
    await loadNovels();
}

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

    const targetView = document.getElementById(`${viewName}-view`);
    if (targetView) {
        targetView.classList.add('active');
    }

    // 特殊处理：切换到地图视图时初始化画布
    if (viewName === 'map') {
        setTimeout(() => {
            initMapCanvas();
            renderMap();
        }, 100);
    }

    // 特殊处理：切换到关系视图时重绘图谱
    if (viewName === 'relationships') {
        setTimeout(() => {
            drawRelationshipGraph();
        }, 100);
    }
}

// 加载所有数据
async function loadAllData() {
    try {
        // 先加载标签，因为其他模块的表单需要用到标签数据
        await loadTags();

        // 然后并行加载其他数据
        await Promise.all([
            loadCharacters(),
            loadScenes(),
            loadForeshadows(),
            loadOutlines(),
            loadTimelineEvents(),
            loadMapLocations(),
            loadRelationships()
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

    // ESC键关闭模态框
    const escHandler = (e) => {
        if (e.key === 'Escape') {
            closeModal();
            document.removeEventListener('keydown', escHandler);
        }
    };
    document.addEventListener('keydown', escHandler);
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

// 显示确认对话框
function showConfirmModal(title, message, onConfirm) {
    createModal(title, `
        <p>${message}</p>
        <div class="modal-actions">
            <button class="btn-secondary" onclick="closeModal()">取消</button>
            <button class="btn-danger" id="confirm-btn">确认</button>
        </div>
    `);

    document.getElementById('confirm-btn').addEventListener('click', () => {
        closeModal();
        onConfirm();
    });
}

// 显示加载状态
function showLoading(container) {
    if (typeof container === 'string') {
        container = document.getElementById(container);
    }
    if (container) {
        container.innerHTML = '<div class="loading">加载中...</div>';
    }
}

// 显示错误状态
function showError(container, message) {
    if (typeof container === 'string') {
        container = document.getElementById(container);
    }
    if (container) {
        container.innerHTML = `<div class="error-state"><p>${message}</p></div>`;
    }
}

// 格式化日期
function formatDate(dateString) {
    if (!dateString) return '';
    const date = new Date(dateString);
    return date.toLocaleDateString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// 防抖函数
function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

// 节流函数
function throttle(func, limit) {
    let inThrottle;
    return function(...args) {
        if (!inThrottle) {
            func.apply(this, args);
            inThrottle = true;
            setTimeout(() => inThrottle = false, limit);
        }
    };
}
