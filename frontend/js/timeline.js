// 时间轴管理功能
let timelineEvents = [];

// 加载所有时间轴事件
async function loadTimelineEvents() {
    try {
        timelineEvents = await api.timelineEvents.getAll();
        renderTimelineEvents();
    } catch (error) {
        console.error('Failed to load timeline events:', error);
    }
}

// 渲染时间轴事件列表
function renderTimelineEvents() {
    const container = document.getElementById('timeline-list');
    if (!container) return;

    if (timelineEvents.length === 0) {
        container.innerHTML = '<div class="empty-state"><h3>暂无时间轴事件</h3><p>点击"添加事件"按钮创建第一个事件</p></div>';
        return;
    }

    container.innerHTML = timelineEvents.map(event => `
        <div class="timeline-item" data-id="${event.id}">
            <h3>${event.title}</h3>
            <div class="meta">
                ${event.eventTime ? `故事时间：${event.eventTime}` : ''}
                ${event.realOrder ? ` | 发生顺序：${event.realOrder}` : ''}
            </div>
            ${event.description ? `<div class="description">${event.description}</div>` : ''}

            ${event.characters && event.characters.length > 0 ? `
                <div class="relations">
                    <strong>涉及人物：</strong>
                    ${event.characters.map(char =>
                        `<span class="relation-tag character" onclick="navigateTo('character', ${char.id})">${char.name}</span>`
                    ).join('')}
                </div>
            ` : ''}

            ${event.scenes && event.scenes.length > 0 ? `
                <div class="relations">
                    <strong>发生场景：</strong>
                    ${event.scenes.map(scene =>
                        `<span class="relation-tag scene" onclick="navigateTo('scene', ${scene.id})">${scene.name}</span>`
                    ).join('')}
                </div>
            ` : ''}

            ${event.foreshadows && event.foreshadows.length > 0 ? `
                <div class="relations">
                    <strong>相关伏笔：</strong>
                    ${event.foreshadows.map(foreshadow =>
                        `<span class="relation-tag foreshadow" onclick="navigateTo('foreshadow', ${foreshadow.id})">${foreshadow.title}</span>`
                    ).join('')}
                </div>
            ` : ''}

            ${event.outlines && event.outlines.length > 0 ? `
                <div class="relations">
                    <strong>对应大纲：</strong>
                    ${event.outlines.map(outline =>
                        `<span class="relation-tag outline" onclick="navigateTo('outline', ${outline.id})">${outline.title}</span>`
                    ).join('')}
                </div>
            ` : ''}

            ${renderElementTags(event.tags)}

            <div class="actions">
                <button class="btn-secondary" onclick="editTimelineEvent(${event.id})">编辑</button>
                <button class="btn-secondary" onclick="manageEventRelations(${event.id})">管理关联</button>
                <button class="btn-secondary" onclick="manageTimelineEventTags(${event.id})">标签</button>
                <button class="btn-danger" onclick="deleteTimelineEvent(${event.id})">删除</button>
            </div>
        </div>
    `).join('');
}

// 搜索时间轴事件
let timelineSearchTimeout;
async function searchTimelineEvents(keyword) {
    clearTimeout(timelineSearchTimeout);
    timelineSearchTimeout = setTimeout(async () => {
        if (keyword.trim()) {
            try {
                timelineEvents = await api.timelineEvents.search(keyword);
                renderTimelineEvents();
            } catch (error) {
                console.error('Search failed:', error);
            }
        } else {
            await loadTimelineEvents();
        }
    }, 300);
}

// 显示添加/编辑事件模态框
function showTimelineEventModal(eventId = null) {
    const event = eventId ? timelineEvents.find(e => e.id === eventId) : null;
    const isEdit = !!event;
    const selectedTagIds = event?.tags ? event.tags.map(t => t.id) : [];

    createModal(isEdit ? '编辑事件' : '添加事件', `
        <form id="timeline-event-form">
            <div class="form-group">
                <label>事件标题 *</label>
                <input type="text" id="event-title" value="${event?.title || ''}" required>
            </div>
            <div class="form-group">
                <label>故事时间</label>
                <input type="text" id="event-time" value="${event?.eventTime || ''}" placeholder="例如：第三天早上、春季">
            </div>
            <div class="form-group">
                <label>发生顺序</label>
                <input type="number" id="event-order" value="${event?.realOrder || ''}" placeholder="用于排序">
            </div>
            <div class="form-group">
                <label>事件描述</label>
                <textarea id="event-description">${event?.description || ''}</textarea>
            </div>
            <div class="form-group">
                <label>标签</label>
                ${generateTagSelector(selectedTagIds, 'timeline-event-form')}
            </div>
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">${isEdit ? '更新' : '创建'}</button>
            </div>
        </form>
    `);

    document.getElementById('timeline-event-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveTimelineEvent(eventId);
    });
}

// 保存时间轴事件
async function saveTimelineEvent(eventId) {
    const data = {
        title: document.getElementById('event-title').value,
        eventTime: document.getElementById('event-time').value,
        realOrder: parseInt(document.getElementById('event-order').value) || null,
        description: document.getElementById('event-description').value
    };

    try {
        let savedEvent;
        if (eventId) {
            savedEvent = await api.timelineEvents.update(eventId, data);
        } else {
            savedEvent = await api.timelineEvents.create(data);
            eventId = savedEvent.id;
        }

        // 保存标签
        const tagIds = getSelectedTagIds('timeline-event-form');
        await api.timelineEvents.setTags(eventId, tagIds);

        closeModal();
        await loadTimelineEvents();
    } catch (error) {
        console.error('Failed to save timeline event:', error);
        alert('保存失败，请重试');
    }
}

// 管理事件关联
function manageEventRelations(eventId) {
    const event = timelineEvents.find(e => e.id === eventId);
    if (!event) return;

    // 获取已关联的ID
    const linkedCharacterIds = (event.characters || []).map(c => c.id);
    const linkedSceneIds = (event.scenes || []).map(s => s.id);
    const linkedForeshadowIds = (event.foreshadows || []).map(f => f.id);
    const linkedOutlineIds = (event.outlines || []).map(o => o.id);

    createModal('管理事件关联', `
        <form id="relations-form">
            <div class="form-group">
                <label>涉及人物</label>
                <div class="checkbox-group">
                    ${characters.map(char => `
                        <label>
                            <input type="checkbox" name="characters" value="${char.id}"
                                ${linkedCharacterIds.includes(char.id) ? 'checked' : ''}>
                            ${char.name}
                        </label>
                    `).join('')}
                </div>
            </div>

            <div class="form-group">
                <label>发生场景</label>
                <div class="checkbox-group">
                    ${scenes.map(scene => `
                        <label>
                            <input type="checkbox" name="scenes" value="${scene.id}"
                                ${linkedSceneIds.includes(scene.id) ? 'checked' : ''}>
                            ${scene.name}
                        </label>
                    `).join('')}
                </div>
            </div>

            <div class="form-group">
                <label>相关伏笔</label>
                <div class="checkbox-group">
                    ${foreshadows.map(foreshadow => `
                        <label>
                            <input type="checkbox" name="foreshadows" value="${foreshadow.id}"
                                ${linkedForeshadowIds.includes(foreshadow.id) ? 'checked' : ''}>
                            ${foreshadow.title}
                        </label>
                    `).join('')}
                </div>
            </div>

            <div class="form-group">
                <label>对应大纲</label>
                <div class="checkbox-group">
                    ${outlines.map(outline => `
                        <label>
                            <input type="checkbox" name="outlines" value="${outline.id}"
                                ${linkedOutlineIds.includes(outline.id) ? 'checked' : ''}>
                            ${outline.title}
                        </label>
                    `).join('')}
                </div>
            </div>

            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存关联</button>
            </div>
        </form>
    `);

    document.getElementById('relations-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await saveEventRelations(eventId);
    });
}

// 保存事件关联
async function saveEventRelations(eventId) {
    const form = document.getElementById('relations-form');

    // 获取选中的关联
    const selectedCharacterIds = Array.from(form.querySelectorAll('input[name="characters"]:checked'))
        .map(cb => parseInt(cb.value));
    const selectedSceneIds = Array.from(form.querySelectorAll('input[name="scenes"]:checked'))
        .map(cb => parseInt(cb.value));
    const selectedForeshadowIds = Array.from(form.querySelectorAll('input[name="foreshadows"]:checked'))
        .map(cb => parseInt(cb.value));
    const selectedOutlineIds = Array.from(form.querySelectorAll('input[name="outlines"]:checked'))
        .map(cb => parseInt(cb.value));

    try {
        // 更新事件关联
        await api.timelineEvents.setRelations(eventId, {
            characterIds: selectedCharacterIds,
            sceneIds: selectedSceneIds,
            foreshadowIds: selectedForeshadowIds,
            outlineIds: selectedOutlineIds
        });

        closeModal();
        await loadTimelineEvents();
    } catch (error) {
        console.error('Failed to save relations:', error);
        alert('保存关联失败，请重试');
    }
}

// 编辑时间轴事件
function editTimelineEvent(id) {
    showTimelineEventModal(id);
}

// 删除时间轴事件
async function deleteTimelineEvent(id) {
    if (!confirm('确定要删除这个事件吗？')) return;

    try {
        await api.timelineEvents.delete(id);
        await loadTimelineEvents();
    } catch (error) {
        console.error('Failed to delete timeline event:', error);
        alert('删除失败，请重试');
    }
}

// 管理时间轴事件标签
function manageTimelineEventTags(eventId) {
    const event = timelineEvents.find(e => e.id === eventId);
    const selectedTagIds = event.tags ? event.tags.map(t => t.id) : [];

    createModal('管理标签 - ' + event.title, `
        <form id="timeline-event-tags-form">
            ${generateTagSelector(selectedTagIds)}
            <div class="modal-actions">
                <button type="button" class="btn-secondary" onclick="closeModal()">取消</button>
                <button type="submit" class="btn-primary">保存</button>
            </div>
        </form>
    `);

    document.getElementById('timeline-event-tags-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tagIds = getSelectedTagIds('timeline-event-tags-form');
        try {
            await api.timelineEvents.setTags(eventId, tagIds);
            closeModal();
            await loadTimelineEvents();
        } catch (error) {
            console.error('Failed to update tags:', error);
            alert('保存失败，请重试');
        }
    });
}
