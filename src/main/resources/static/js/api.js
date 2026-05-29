// API 请求封装
const API_BASE = '';

function createApiClient() {
    async function request(method, url, body, options = {}) {
        const token = localStorage.getItem('auth_token');
        const headers = { ...options.headers };

        if (token) {
            headers['Authorization'] = 'Bearer ' + token;
        }

        if (body && !(body instanceof FormData)) {
            headers['Content-Type'] = 'application/json';
        }

        const res = await fetch(API_BASE + url, {
            method,
            headers,
            body: body instanceof FormData ? body : (body ? JSON.stringify(body) : undefined),
        });

        const data = await res.json();

        if (data.code === 401) {
            localStorage.removeItem('auth_token');
            localStorage.removeItem('agent_session_id');
            window.location.reload();
            throw new Error('认证已过期');
        }

        if (data.code !== 200) {
            throw new Error(data.message || '请求失败');
        }

        return data.data;
    }

    return {
        // 认证
        login: (username, password) => request('POST', '/api/auth/login', { username, password }),
        register: (username, password) => request('POST', '/api/auth/register', { username, password }),
        me: () => request('GET', '/api/auth/me'),

        // 会话
        listSessions: () => request('GET', '/api/sessions'),
        createSession: () => request('POST', '/api/sessions'),
        getSession: (id) => request('GET', `/api/sessions/${id}`),
        deleteSession: (id) => request('DELETE', `/api/sessions/${id}`),

        // 聊天
        sendMessage: (sessionId, message) => request('POST', `/api/sessions/${sessionId}/chat`, { message }),
        getHistory: (sessionId) => request('GET', `/api/sessions/${sessionId}/history`),

        // 技能（全局）
        listSkills: () => request('GET', '/api/skills'),
        getSkill: (id) => request('GET', `/api/skills/${id}`),
        setSkillRoot: (directory) => request('POST', '/api/skills/set-root', { directory }),

        // 技能（会话）
        getEnabledSkills: (sessionId) => request('GET', `/api/sessions/${sessionId}/skills`),
        enableSkill: (sessionId, skillId) => request('POST', `/api/sessions/${sessionId}/skills/${skillId}/enable`),
        disableSkill: (sessionId, skillId) => request('POST', `/api/sessions/${sessionId}/skills/${skillId}/disable`),

        // VNC
        getAioEndpoint: (sessionId) => request('GET', `/api/sessions/${sessionId}/aio/endpoint`),

        // 文件
        uploadFile: (sessionId, file) => {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('sessionId', sessionId);
            return request('POST', '/api/files/upload', formData);
        },
    };
}

const api = createApiClient();
