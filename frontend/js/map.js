// 地图管理功能
let mapLocations = [];
let mapCanvas, mapCtx;
let mapBackgroundImage = null;  // 地图背景图片
let mapBackgroundImageId = null; // 背景图片ID

// 初始化画布
function initMapCanvas() {
    mapCanvas = document.getElementById('map-canvas');
    if (!mapCanvas) return;

    mapCtx = mapCanvas.getContext('2d');

    // 画布点击事件 - 添加位置
    mapCanvas.addEventListener('click', (e) => {
        const rect = mapCanvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        // 预填充坐标到模态框
        showMapLocationModal(null, Math.floor(x), Math.floor(y));
    });

    // 加载保存的背景图片
    loadMapBackground();
}

// 加载地图背景图片
async function loadMapBackground() {
    const savedBgId = localStorage.getItem(`mapBackground_${getCurrentNovelId()}`);
    if (savedBgId) {
        mapBackgroundImageId = parseInt(savedBgId);
        const img = new Image();
        img.onload = () => {
            mapBackgroundImage = img;
            renderMap();
        };
        img.src = api.images.getFileUrl(mapBackgroundImageId);
    }
}

// 设置地图背景图片
async function setMapBackground() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = async (e) => {
        const file = e.target.files[0];
        if (!file) return;

        try {
            const uploadedImage = await uploadImage(file, 'map_background');
            mapBackgroundImageId = uploadedImage.id;
            localStorage.setItem(`mapBackground_${getCurrentNovelId()}`, mapBackgroundImageId);

            const img = new Image();
            img.onload = () => {
                mapBackgroundImage = img;
                renderMap();
            };
            img.src = api.images.getFileUrl(mapBackgroundImageId);
            alert('背景图片设置成功！');
        } catch (error) {
            console.error('Failed to upload background image:', error);
            alert('上传背景图片失败，请重试');
        }
    };
    input.click();
}

// 清除地图背景图片
function clearMapBackground() {
    if (!confirm('确定要清除地图背景图片吗？')) return;

    mapBackgroundImage = null;
    mapBackgroundImageId = null;
    localStorage.removeItem(`mapBackground_${getCurrentNovelId()}`);
    renderMap();
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
    if (!mapCtx) initMapCanvas();
    if (!mapCtx) return;

    // 清空画布
    mapCtx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);

    // 绘制背景图片（如果有）
    if (mapBackgroundImage) {
        mapCtx.drawImage(mapBackgroundImage, 0, 0, mapCanvas.width, mapCanvas.height);
    } else {
        // 没有背景图片时绘制网格
        mapCtx.strokeStyle = '#e0e0e0';
        mapCtx.lineWidth = 1;

        for (let x = 0; x <= mapCanvas.width; x += 50) {
            mapCtx.beginPath();
            mapCtx.moveTo(x, 0);
            mapCtx.lineTo(x, mapCanvas.height);
            mapCtx.stroke();
        }

        for (let y = 0; y <= mapCanvas.height; y += 50) {
            mapCtx.beginPath();
            mapCtx.moveTo(0, y);
            mapCtx.lineTo(mapCanvas.width, y);
            mapCtx.stroke();
        }
    }

    // 绘制位置点
    mapLocations.forEach(location => {
        if (location.positionX != null && location.positionY != null) {
            // 如果有父位置，绘制连线
            if (location.parentLocation) {
                const parent = mapLocations.find(l => l.id === location.parentLocation.id);
                if (parent && parent.positionX != null && parent.positionY != null) {
                    mapCtx.strokeStyle = '#95a5a6';
                    mapCtx.lineWidth = 2;
                    mapCtx.beginPath();
                    mapCtx.moveTo(parent.positionX, parent.positionY);
                    mapCtx.lineTo(location.positionX, location.positionY);
                    mapCtx.stroke();
                }
            }
        }
    });

    // 绘制位置点（在连线之后，确保点在线上方）
    mapLocations.forEach(location => {
        if (location.positionX != null && location.positionY != null) {
            // 绘制点
            mapCtx.fillStyle = '#3498db';
            mapCtx.beginPath();
            mapCtx.arc(location.positionX, location.positionY, 8, 0, 2 * Math.PI);
            mapCtx.fill();

            // 绘制标签
            mapCtx.fillStyle = '#2c3e50';
            mapCtx.font = '12px Microsoft YaHei';
            mapCtx.fillText(location.name, location.positionX + 12, location.positionY + 4);
        }
    });
}

// 渲染地图位置列表
function renderMapLocationsList() {
    const container = document.getElementById('map-locations-list');
    if (!container) return;

    if (mapLocations.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无地图位置</h3><p>点击地图或"添加位置"按钮创建位置</p></div>';
        return;
    }

    container.innerHTML = mapLocations.map(location => `
        <div class="list-item" data-id="${location.id}">
            <div class="location-info">
                ${location.locationImage ? `<img src="${api.images.getFileUrl(location.locationImage)}" alt="${location.name}" class="location-thumbnail">` : ''}
                <div>
                    <h4>${location.name}</h4>
                    <p>
                        ${location.locationType ? `类型：${location.locationType} | ` : ''}
                        坐标：(${location.positionX || 0}, ${location.positionY || 0})
                        ${location.parentLocation ? ` | 上级：${location.parentLocation.name}` : ''}
                    </p>
                    ${location.description ? `<p>${location.description}</p>` : ''}
                </div>
            </div>
            ${renderElementTags(location.tags)}
            <div class="actions">
                <button class="btn-secondary" onclick="editMapLocation(${location.id})">编辑</button>
                <button class="btn-secondary" onclick="manageMapLocationTags(${location.id})">标签</button>
                <button class="btn-danger" onclick="deleteMapLocation(${location.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 搜索地图位置
let mapSearchTimeout;
async function searchMapLocations(keyword) {
    clearTimeout(mapSearchTimeout);
    mapSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                mapLocations = await api.mapLocations.search(keyword);
                renderMapLocationsList();
                renderMap();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadMapLocations();
        }
    }, 300);
}

// 显示添加/编辑地图位置模态框
function showMapLocationModal(locationId = null, defaultX = null, defaultY = null) {
    const location = locationId ? mapLocations.find(l => l.id === locationId) : null;
    const isEdit = !!location;
    const selectedTagIds = location?.tags ? location.tags.map(t => t.id) : [];

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
            <div class="form-group">
                <label>位置图片</label>
                <input type="file" id="location-image" accept="image/*">
                ${location?.locationImage ? '<p class="current-image">已有位置图片</p>' : ''}
            </div>
            <div class="form-group">
                <label>标签</label>
                ${generateTagSelector(selectedTagIds, 'map-location-form')}
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
    const fileInput = document.getElementById('location-image');
    let locationImage = locationId ? mapLocations.find(l => l.id === locationId)?.locationImage : null;

    if (fileInput.files.length > 0) {
        try {
            const uploadedImage = await uploadImage(fileInput.files[0], 'location');
            locationImage = uploadedImage.id;
        } catch (error) {
            console.error('Failed to upload image:', error);
        }
    }

    const parentId = document.getElementById('location-parent').value;

    const data = {
        name: document.getElementById('location-name').value,
        locationType: document.getElementById('location-type').value,
        positionX: parseInt(document.getElementById('location-x').value) || 0,
        positionY: parseInt(document.getElementById('location-y').value) || 0,
        description: document.getElementById('location-description').value,
        parentLocation: parentId ? { id: parseInt(parentId) } : null,
        locationImage: locationImage
    };

    try {
        let savedLocation;
        if (locationId) {
            savedLocation = await api.mapLocations.update(locationId, data);
        } else {
            savedLocation = await api.mapLocations.create(data);
            locationId = savedLocation.id;
        }

        // 保存标签
        const tagIds = getSelectedTagIds('map-location-form');
        await api.mapLocations.setTags(locationId, tagIds);

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

// 管理地图位置标签
function manageMapLocationTags(locationId) {
    const location = mapLocations.find(l => l.id === locationId);
    const selectedTagIds = location.tags ? location.tags.map(t => t.id) : [];

    createModal('管理标签 - ' + location.name, `
        <form id="map-location-tags-form">
            ${generateTagSelector(selectedTagIds)}
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存</button>
            </div>
        </form>
    `);

    document.getElementById('map-location-tags-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tagIds = getSelectedTagIds('map-location-tags-form');
        try {
            await api.mapLocations.setTags(locationId, tagIds);
            closeModal();
            await loadMapLocations();
        } catch (error) {
            console.error('Failed to update tags:', error);
            alert('保存失败，请重试');
        }
    });
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
