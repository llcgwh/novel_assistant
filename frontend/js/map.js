// 地图管理功能
let mapLocations = [];
let canvas, ctx;

// 初始化画布
function initMapCanvas() {
    canvas = document.getElementById('map-canvas');
    if (!canvas) return;

    ctx = canvas.getContext('2d');

    // 画布点击事件 - 添加位置
    canvas.addEventListener('click', (e) => {
        const rect = canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        // 预填充坐标到模态框
        showMapLocationModal(null, Math.floor(x), Math.floor(y));
    });
}

// 加载所有地图位置
async function loadMapLocations() {
    try {
        mapLocations = await api.mapLocations.getAll();
        if (document.getElementById('map-view').classList.contains('active')) {
            initMapCanvas();
            renderMap();
        }
        renderMapLocationsList();
    } catch (error) {
        console.error('Failed to load map locations:', error);
    }
}

// 渲染地图
function renderMap() {
    if (!ctx) initMapCanvas();
    if (!ctx) return;

    // 清空画布
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // 绘制网格
    ctx.strokeStyle = '#e0e0e0';
    ctx.lineWidth = 1;

    for (let x = 0; x <= canvas.width; x += 50) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, canvas.height);
        ctx.stroke();
    }

    for (let y = 0; y <= canvas.height; y += 50) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(canvas.width, y);
        ctx.stroke();
    }

    // 绘制位置点
    mapLocations.forEach(location => {
        if (location.positionX != null && location.positionY != null) {
            // 绘制点
            ctx.fillStyle = '#3498db';
            ctx.beginPath();
            ctx.arc(location.positionX, location.positionY, 8, 0, 2 * Math.PI);
            ctx.fill();

            // 绘制标签
            ctx.fillStyle = '#2c3e50';
            ctx.font = '12px Arial';
            ctx.fillText(location.name, location.positionX + 12, location.positionY + 4);

            // 如果有父位置，绘制连线
            if (location.parentLocation) {
                const parent = mapLocations.find(l => l.id === location.parentLocation.id);
                if (parent && parent.positionX != null && parent.positionY != null) {
                    ctx.strokeStyle = '#95a5a6';
                    ctx.lineWidth = 2;
                    ctx.beginPath();
                    ctx.moveTo(parent.positionX, parent.positionY);
                    ctx.lineTo(location.positionX, location.positionY);
                    ctx.stroke();
                }
            }
        }
    });
}

// 渲染地图位置列表
function renderMapLocationsList() {
    const container = document.getElementById('map-locations-list');
    if (mapLocations.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无地图位置</h3><p>点击地图或"添加位置"按钮创建位置</p></div>';
        return;
    }

    container.innerHTML = mapLocations.map(location => `
        <div class="list-item" data-id="${location.id}">
            <div>
                <h4>${location.name}</h4>
                <p>
                    ${location.locationType ? `类型：${location.locationType} | ` : ''}
                    坐标：(${location.positionX || 0}, ${location.positionY || 0})
                    ${location.parentLocation ? ` | 上级：${location.parentLocation.name}` : ''}
                </p>
                ${location.description ? `<p>${location.description}</p>` : ''}
            </div>
            <div class="actions">
                <button class="btn-secondary" onclick="editMapLocation(${location.id})">编辑</button>
                <button class="btn-danger" onclick="deleteMapLocation(${location.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 显示添加/编辑地图位置模态框
function showMapLocationModal(locationId = null, defaultX = null, defaultY = null) {
    const location = locationId ? mapLocations.find(l => l.id === locationId) : null;
    const isEdit = !!location;

    const posX = location?.positionX ?? defaultX ?? '';
    const posY = location?.positionY ?? defaultY ?? '';

    createModal(isEdit ? '编辑位置' : '添加位置', `
        <form id="map-location-form">
            <div class="form-group">
                <label>位置名称 *</label>
                <input type="text" id="location-name" value="${location?.name || ''}" required>
            </div>
            <div class="form-group">
                <label>位置类型</label>
                <input type="text" id="location-type" value="${location?.locationType || ''}" placeholder="例如：城市、建筑、地标">
            </div>
            <div class="form-group">
                <label>X 坐标</label>
                <input type="number" id="location-x" value="${posX}">
            </div>
            <div class="form-group">
                <label>Y 坐标</label>
                <input type="number" id="location-y" value="${posY}">
            </div>
            <div class="form-group">
                <label>上级位置</label>
                <select id="location-parent">
                    <option value="">无</option>
                    ${mapLocations
                        .filter(l => !locationId || l.id !== locationId)
                        .map(l => `
                            <option value="${l.id}" ${location?.parentLocation?.id === l.id ? 'selected' : ''}>
                                ${l.name}
                            </option>
                        `).join('')}
                </select>
            </div>
            <div class="form-group">
                <label>描述</label>
                <textarea id="location-description">${location?.description || ''}</textarea>
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('map-location-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveMapLocation(locationId);
    });
}

// 保存地图位置
async function saveMapLocation(locationId) {
    const parentId = document.getElementById('location-parent').value;

    const data = {
        name: document.getElementById('location-name').value,
        locationType: document.getElementById('location-type').value,
        positionX: parseInt(document.getElementById('location-x').value) || 0,
        positionY: parseInt(document.getElementById('location-y').value) || 0,
        description: document.getElementById('location-description').value,
        parentLocation: parentId ? { id: parseInt(parentId) } : null
    };

    try {
        if (locationId) {
            await api.mapLocations.update(locationId, data);
        } else {
            await api.mapLocations.create(data);
        }
        closeModal();
        await loadMapLocations();
    } catch (error) {
        console.error('Failed to save map location:', error);
        alert('保存失败，请重试');
    }
}

// 编辑地图位置
function editMapLocation(id) {
    showMapLocationModal(id);
}

// 删除地图位置
async function deleteMapLocation(id) {
    if (!confirm('确定要删除这个位置吗？')) return;

    try {
        await api.mapLocations.delete(id);
        await loadMapLocations();
    } catch (error) {
        console.error('Failed to delete map location:', error);
        alert('删除失败，请重试');
    }
}

// 监听视图切换，初始化地图
document.addEventListener('DOMContentLoaded', () => {
    const mapNavBtn = document.querySelector('[data-view="map"]');
    if (mapNavBtn) {
        mapNavBtn.addEventListener('click', () => {
            setTimeout(() => {
                initMapCanvas();
                renderMap();
            }, 100);
        });
    }
});
