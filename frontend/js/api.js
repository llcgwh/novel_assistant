// API 基础配置
const API_BASE_URL = 'http://localhost:8080/api';

// API 请求封装
const api = {
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

    // 人物相关 API
    characters: {
        getAll: () => api.request('/characters'),
        getById: (id) => api.request(`/characters/${id}`),
        create: (data) => api.request('/characters', 'POST', data),
        update: (id, data) => api.request(`/characters/${id}`, 'PUT', data),
        delete: (id) => api.request(`/characters/${id}`, 'DELETE')
    },

    // 场景相关 API
    scenes: {
        getAll: () => api.request('/scenes'),
        getById: (id) => api.request(`/scenes/${id}`),
        create: (data) => api.request('/scenes', 'POST', data),
        update: (id, data) => api.request(`/scenes/${id}`, 'PUT', data),
        delete: (id) => api.request(`/scenes/${id}`, 'DELETE')
    },

    // 伏笔相关 API
    foreshadows: {
        getAll: () => api.request('/foreshadows'),
        getById: (id) => api.request(`/foreshadows/${id}`),
        create: (data) => api.request('/foreshadows', 'POST', data),
        update: (id, data) => api.request(`/foreshadows/${id}`, 'PUT', data),
        delete: (id) => api.request(`/foreshadows/${id}`, 'DELETE')
    },

    // 大纲相关 API
    outlines: {
        getAll: () => api.request('/outlines'),
        getById: (id) => api.request(`/outlines/${id}`),
        create: (data) => api.request('/outlines', 'POST', data),
        update: (id, data) => api.request(`/outlines/${id}`, 'PUT', data),
        delete: (id) => api.request(`/outlines/${id}`, 'DELETE')
    },

    // 时间轴事件相关 API
    timelineEvents: {
        getAll: () => api.request('/timeline-events'),
        getById: (id) => api.request(`/timeline-events/${id}`),
        create: (data) => api.request('/timeline-events', 'POST', data),
        update: (id, data) => api.request(`/timeline-events/${id}`, 'PUT', data),
        delete: (id) => api.request(`/timeline-events/${id}`, 'DELETE'),
        addCharacters: (id, characterIds) => api.request(`/timeline-events/${id}/characters`, 'POST', characterIds),
        addScenes: (id, sceneIds) => api.request(`/timeline-events/${id}/scenes`, 'POST', sceneIds),
        addForeshadows: (id, foreshadowIds) => api.request(`/timeline-events/${id}/foreshadows`, 'POST', foreshadowIds),
        addOutlines: (id, outlineIds) => api.request(`/timeline-events/${id}/outlines`, 'POST', outlineIds)
    },

    // 地图位置相关 API
    mapLocations: {
        getAll: () => api.request('/map-locations'),
        getById: (id) => api.request(`/map-locations/${id}`),
        create: (data) => api.request('/map-locations', 'POST', data),
        update: (id, data) => api.request(`/map-locations/${id}`, 'PUT', data),
        delete: (id) => api.request(`/map-locations/${id}`, 'DELETE')
    }
};
