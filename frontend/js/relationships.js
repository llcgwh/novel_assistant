// 人物关系管理功能
let relationships = [];

// 加载人物关系
async function loadRelationships() {
    try {
        relationships = await api.relationships.getAll();
        renderRelationships();
        drawRelationshipGraph();
    } catch (error) {
        console.error('Failed to load relationships:', error);
    }
}

// 渲染关系列表
function renderRelationships() {
    const container = document.getElementById('relationships-list');
    if (!container) return;

    if (relationships.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无人物关系</h3><p>点击"添加关系"按钮创建人物关系</p></div>';
        return;
    }

    container.innerHTML = relationships.map(rel => {
        const char1 = characters.find(c => c.id === rel.characterId1);
        const char2 = characters.find(c => c.id === rel.characterId2);
        return `
            <div class="list-item" data-id="${rel.id}">
                <div class="relationship-info">
                    <h4>${char1?.name || '未知'} - ${char2?.name || '未知'}</h4>
                    <p><span class="label">关系类型：</span>${rel.relationshipType || '未定义'}</p>
                    ${rel.description ? `<p>${rel.description}</p>` : ''}
                </div>
                <div class="actions">
                    <button class="btn-secondary" onclick="editRelationship(${rel.id})">编辑</button>
                    <button class="btn-danger" onclick="deleteRelationship(${rel.id})">删除</button>
                </div>
            </div>
        `;
    }).join('');
}

// 绘制关系图谱
function drawRelationshipGraph() {
    const canvas = document.getElementById('relationship-canvas');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    if (characters.length === 0) {
        ctx.fillStyle = '#7f8c8d';
        ctx.font = '16px Microsoft YaHei';
        ctx.textAlign = 'center';
        ctx.fillText('暂无人物数据', canvas.width / 2, canvas.height / 2);
        return;
    }

    // 计算节点位置（圆形布局）
    const centerX = canvas.width / 2;
    const centerY = canvas.height / 2;
    const radius = Math.min(centerX, centerY) - 80;

    const nodePositions = {};
    characters.forEach((char, index) => {
        const angle = (2 * Math.PI * index) / characters.length - Math.PI / 2;
        nodePositions[char.id] = {
            x: centerX + radius * Math.cos(angle),
            y: centerY + radius * Math.sin(angle),
            name: char.name,
            role: char.role
        };
    });

    // 绘制关系线
    const relationshipColors = {
        '朋友': '#27ae60',
        '敌人': '#e74c3c',
        '恋人': '#e91e63',
        '亲属': '#9b59b6',
        '同事': '#3498db',
        '师徒': '#f39c12'
    };

    relationships.forEach(rel => {
        const pos1 = nodePositions[rel.characterId1];
        const pos2 = nodePositions[rel.characterId2];
        if (pos1 && pos2) {
            ctx.beginPath();
            ctx.moveTo(pos1.x, pos1.y);
            ctx.lineTo(pos2.x, pos2.y);
            ctx.strokeStyle = relationshipColors[rel.relationshipType] || '#95a5a6';
            ctx.lineWidth = 2;
            ctx.stroke();

            // 绘制关系标签
            const midX = (pos1.x + pos2.x) / 2;
            const midY = (pos1.y + pos2.y) / 2;
            ctx.fillStyle = '#2c3e50';
            ctx.font = '12px Microsoft YaHei';
            ctx.textAlign = 'center';
            ctx.fillText(rel.relationshipType || '', midX, midY - 5);
        }
    });

    // 绘制节点
    Object.entries(nodePositions).forEach(([id, pos]) => {
        // 节点圆圈
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, 30, 0, 2 * Math.PI);
        ctx.fillStyle = '#3498db';
        ctx.fill();
        ctx.strokeStyle = '#2980b9';
        ctx.lineWidth = 3;
        ctx.stroke();

        // 节点名称
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 12px Microsoft YaHei';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(pos.name.substring(0, 4), pos.x, pos.y);

        // 角色标签
        if (pos.role) {
            ctx.fillStyle = '#7f8c8d';
            ctx.font = '10px Microsoft YaHei';
            ctx.fillText(pos.role, pos.x, pos.y + 45);
        }
    });
}

// 显示关系模态框
function showRelationshipModal(relationshipId = null) {
    const relationship = relationshipId ? relationships.find(r => r.id === relationshipId) : null;
    const isEdit = !!relationship;

    const characterOptions = characters.map(c =>
        `<option value="${c.id}">${c.name}</option>`
    ).join('');

    createModal(isEdit ? '编辑人物关系' : '添加人物关系', `
        <form id="relationship-form">
            <div class="form-group">
                <label>人物1 *</label>
                <select id="rel-char1" required>
                    <option value="">请选择人物</option>
                    ${characterOptions}
                </select>
            </div>
            <div class="form-group">
                <label>人物2 *</label>
                <select id="rel-char2" required>
                    <option value="">请选择人物</option>
                    ${characterOptions}
                </select>
            </div>
            <div class="form-group">
                <label>关系类型</label>
                <select id="rel-type">
                    <option value="">请选择关系类型</option>
                    <option value="朋友">朋友</option>
                    <option value="敌人">敌人</option>
                    <option value="恋人">恋人</option>
                    <option value="亲属">亲属</option>
                    <option value="同事">同事</option>
                    <option value="师徒">师徒</option>
                    <option value="其他">其他</option>
                </select>
            </div>
            <div class="form-group">
                <label>关系描述</label>
                <textarea id="rel-description">${relationship?.description || ''}</textarea>
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    if (relationship) {
        document.getElementById('rel-char1').value = relationship.characterId1;
        document.getElementById('rel-char2').value = relationship.characterId2;
        document.getElementById('rel-type').value = relationship.relationshipType || '';
    }

    document.getElementById('relationship-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveRelationship(relationshipId);
    });
}

// 保存关系
async function saveRelationship(relationshipId) {
    const data = {
        characterId1: parseInt(document.getElementById('rel-char1').value),
        characterId2: parseInt(document.getElementById('rel-char2').value),
        relationshipType: document.getElementById('rel-type').value,
        description: document.getElementById('rel-description').value
    };

    if (data.characterId1 === data.characterId2) {
        alert('请选择两个不同的人物');
        return;
    }

    try {
        if (relationshipId) {
            await api.relationships.update(relationshipId, data);
        } else {
            await api.relationships.create(data);
        }
        closeModal();
        await loadRelationships();
    } catch (error) {
        console.error('Failed to save relationship:', error);
        alert('保存失败，请重试');
    }
}

// 编辑关系
function editRelationship(id) {
    showRelationshipModal(id);
}

// 删除关系
async function deleteRelationship(id) {
    if (!confirm('确定要删除这个关系吗？')) return;

    try {
        await api.relationships.delete(id);
        await loadRelationships();
    } catch (error) {
        console.error('Failed to delete relationship:', error);
        alert('删除失败，请重试');
    }
}
