// API 基础配置
let currentNovelId = null;
const API_BASE_URL = 'http://localhost:8080/api';

// API 请求封装
const api = {
    // 基础URL
    baseUrl: API_BASE_URL,

    // 通用请求方法
    async request(endpoint, method = 'GET', data = null) {
        const config = {
            method,
            headers: {
                'Content-Type': 'application/json',
            }
        };

        if (data) {
            config.body = JSON.stringify(data);
        }

        try {
            const response = await fetch(`${API_BASE_URL}${endpoint}`, config);
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return method === 'DELETE' ? null : await response.json();
        } catch (error) {
            console.error('API request failed:', error);
            throw error;
        }
    },

    // 小说管理 API
    novels: {
        getAll: () => api.request('/novels'),
        getById: (id) => api.request(`/novels/${id}`),  // 保持API完整性，尽管当前未被直接使用
        create: (data) => api.request('/novels', 'POST', data),
        update: (id, data) => api.request(`/novels/${id}`, 'PUT', data),
        delete: (id) => api.request(`/novels/${id}`, 'DELETE'),
        search: (title) => api.request(`/novels/search?title=${encodeURIComponent(title)}`)  // 保持API完整性，尽管当前未被直接使用
    },

    // 标签 API
    tags: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/${id}`);
        },  // 保持API完整性，尽管当前未被直接使用
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/${id}`, 'DELETE');
        },
        search: (name) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/search?name=${encodeURIComponent(name)}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/${id}/tags/${tagId}`, 'POST');
        },  // 保持API完整性，尽管当前未被直接使用
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/tags/${id}/tags/${tagId}`, 'DELETE');
        }  // 保持API完整性，尽管当前未被直接使用
    },

    // 搜索 API
    search: {
        global: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/search?keyword=${encodeURIComponent(keyword)}`);
        },
        byTag: (tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/search/tag/${tagId}`);
        },
        byTagName: (tagName) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/search/tag-name?tagName=${encodeURIComponent(tagName)}`);
        }
    },

    // 导出 API
    export: {
        toJson: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return `${API_BASE_URL}/novels/${currentNovelId}/export/json`;
        },
        toMarkdown: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return `${API_BASE_URL}/novels/${currentNovelId}/export/markdown`;
        },
        charactersToMarkdown: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return `${API_BASE_URL}/novels/${currentNovelId}/export/characters/markdown`;
        },
        outlinesToMarkdown: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return `${API_BASE_URL}/novels/${currentNovelId}/export/outlines/markdown`;
        }
    },

    // 可视化 API
    visualization: {
        getCharacterNetwork: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/visualization/character-network`);
        }  // 保持API完整性，尽管当前未被直接使用
    },

    // 人物关系 API
    relationships: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/relationships`);
        },
        getByCharacter: (characterId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/relationships/character/${characterId}`);
        },  // 保持API完整性，尽管当前未被直接使用
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/relationships`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/relationships/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/relationships/${id}`, 'DELETE');
        }
    },

    // 图片 API
    images: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/images`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/images/${id}`);
        },  // 保持API完整性，尽管当前未被直接使用
        getByType: (type) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/images/type/${type}`);
        },  // 保持API完整性，尽管当前未被直接使用
        getFileUrl: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return `${API_BASE_URL}/novels/${currentNovelId}/images/${id}/file`;
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/images/${id}`, 'DELETE');
        }  // 保持API完整性，尽管当前未被直接使用
    },

    // 人物相关 API
    characters: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/search?keyword=${encodeURIComponent(keyword)}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/characters/${id}/tags`, 'PUT', tagIds);
        }
    },

    // 场景相关 API
    scenes: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/search?keyword=${encodeURIComponent(keyword)}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/scenes/${id}/tags`, 'PUT', tagIds);
        }
    },

    // 伏笔相关 API
    foreshadows: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/search?keyword=${encodeURIComponent(keyword)}`);
        },
        getByStatus: (status) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/status/${status}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/foreshadows/${id}/tags`, 'PUT', tagIds);
        }
    },

    // 大纲相关 API
    outlines: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/search?keyword=${encodeURIComponent(keyword)}`);
        },
        getByStatus: (status) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/status/${status}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/outlines/${id}/tags`, 'PUT', tagIds);
        }
    },

    // 时间轴事件相关 API
    timelineEvents: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/search?keyword=${encodeURIComponent(keyword)}`);
        },
        addCharacters: (id, characterIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/characters`, 'POST', characterIds);
        },
        addScenes: (id, sceneIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/scenes`, 'POST', sceneIds);
        },
        addForeshadows: (id, foreshadowIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/foreshadows`, 'POST', foreshadowIds);
        },
        addOutlines: (id, outlineIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/outlines`, 'POST', outlineIds);
        },
        setRelations: (id, relations) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/relations`, 'PUT', relations);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/timeline-events/${id}/tags`, 'PUT', tagIds);
        }
    },

    // 地图位置相关 API
    mapLocations: {
        getAll: () => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations`);
        },
        getById: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}`);
        },
        create: (data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations`, 'POST', data);
        },
        update: (id, data) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}`, 'PUT', data);
        },
        delete: (id) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}`, 'DELETE');
        },
        search: (keyword) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/search?keyword=${encodeURIComponent(keyword)}`);
        },
        getByType: (type) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/type/${type}`);
        },
        addTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}/tags/${tagId}`, 'POST');
        },
        removeTag: (id, tagId) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}/tags/${tagId}`, 'DELETE');
        },
        setTags: (id, tagIds) => {
            if (!currentNovelId) throw new Error('No novel selected');
            return api.request(`/novels/${currentNovelId}/map-locations/${id}/tags`, 'PUT', tagIds);
        }
    }
};

// 设置当前小说ID
function setCurrentNovel(novelId) {
    currentNovelId = novelId;
    localStorage.setItem('currentNovelId', novelId);
}

// 获取当前小说ID
function getCurrentNovelId() {
    if (!currentNovelId) {
        currentNovelId = localStorage.getItem('currentNovelId');
    }
    return currentNovelId;
}

// 上传图片
async function uploadImage(file, imageType) {
    if (!currentNovelId) throw new Error('No novel selected');
    
    const formData = new FormData();
    formData.append('file', file);
    formData.append('imageType', imageType);

    const response = await fetch(`${API_BASE_URL}/novels/${currentNovelId}/images`, {
        method: 'POST',
        body: formData
    });

    if (!response.ok) {
        throw new Error('Upload failed');
    }

    return await response.json();
}