// 对话页组件
const ChatPage = {
    template: `
        <div class="chat-page" style="display: flex; gap: 0;">
            <!-- 左侧：对话区域 -->
            <div style="flex: 1; min-width: 0;">
                <!-- 会话选择器 -->
                <div class="session-selector">
                    <label>会话：</label>
                    <select v-model="currentSessionId" @change="switchSession">
                        <option value="">-- 新建会话 --</option>
                        <option v-for="s in sessions" :key="s.sessionId" :value="s.sessionId">
                            {{ s.sessionId.substring(0, 8) }}... ({{ s.enabledSkillIds ? s.enabledSkillIds.length : 0 }} 技能)
                        </option>
                    </select>
                    <button @click="createSession">＋ 新建</button>
                    <button v-if="currentSessionId" @click="copyId" :style="{ background: copied ? '#4CAF50' : '#2196F3' }">
                        {{ copied ? '已复制!' : '复制 ID' }}
                    </button>
                </div>

                <!-- 对话面板 -->
                <div class="chat-panel" v-if="currentSessionId">
                    <div class="chat-header">
                        <h3>💬 对话</h3>
                    </div>
                    <div class="chat-messages" ref="messagesEl">
                        <div v-if="messages.length === 0" class="empty">开始对话</div>
                        <div v-for="msg in messages" :key="msg.timestamp" :class="['chat-message', msg.role]">
                            <div class="role-label">{{ msg.role === 'user' ? '你' : '助手' }}</div>
                            <div class="bubble" v-html="renderContent(msg)"></div>
                        </div>
                    </div>
                    <!-- 上传区域 -->
                    <div class="upload-area">
                        <label class="upload-btn">
                            <span>📎</span>
                            <span>添加文件</span>
                            <input type="file" multiple @change="handleFileSelect">
                        </label>
                        <div class="upload-file-list" v-if="pendingFiles.length > 0">
                            <span class="upload-file-tag" v-for="(file, index) in pendingFiles" :key="index">
                                <span>{{ getFileIcon(file.name) }}</span>
                                <span>{{ file.name }}</span>
                                <span class="remove-btn" @click="removeFile(index)">×</span>
                            </span>
                        </div>
                    </div>
                    <div class="chat-input-area">
                        <input
                            v-model="inputText"
                            placeholder="输入消息..."
                            @keyup.enter="send"
                        >
                        <button @click="send" :disabled="sending">
                            {{ sending ? '处理中...' : '发送' }}
                        </button>
                    </div>
                </div>

                <div v-else class="empty" style="height: 60vh; display: flex; align-items: center; justify-content: center;">
                    请先创建或选择会话
                </div>
            </div>

            <!-- 右侧：VNC 面板 -->
            <div v-if="vncOpen" class="vnc-resize-handle" @mousedown="startResize"></div>
            <div class="vnc-panel" :class="{ open: vncOpen }" :style="{ width: vncOpen ? vncWidth + '%' : '0' }">
                <div class="vnc-header">
                    <h3>沙箱实时视图</h3>
                    <div class="vnc-actions">
                        <span class="vnc-status">{{ vncStatus }}</span>
                        <button @click="resizeVnc(-10)" title="缩小">-</button>
                        <button @click="resizeVnc(10)" title="放大">+</button>
                        <button @click="toggleVnc" title="关闭">✕</button>
                    </div>
                </div>
                <div class="vnc-container">
                    <iframe v-if="vncUrl" :src="vncUrl"></iframe>
                    <div v-else class="vnc-placeholder">{{ vncPlaceholder }}</div>
                </div>
            </div>

            <!-- VNC 浮动按钮 -->
            <button class="vnc-float-btn" @click="toggleVnc" :style="{ background: vncOpen ? '#f44336' : '#4CAF50' }">
                {{ vncOpen ? '✕' : '◀' }}
            </button>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const messagesEl = Vue.ref(null);

        const sessions = Vue.ref([]);
        const currentSessionId = Vue.ref(store.currentSessionId || '');
        const messages = Vue.ref([]);
        const inputText = Vue.ref('');
        const sending = Vue.ref(false);
        const pendingFiles = Vue.ref([]);
        const copied = Vue.ref(false);

        // VNC 状态
        const vncOpen = Vue.ref(false);
        const vncUrl = Vue.ref('');
        const vncStatus = Vue.ref('未连接');
        const vncPlaceholder = Vue.ref('请先创建会话');
        const vncWidth = Vue.ref(70);
        let isResizing = false;
        let startX = 0;

        // 加载会话列表
        const loadSessions = async () => {
            try {
                sessions.value = await api.listSessions();
            } catch (e) {
                console.error('加载会话列表失败:', e);
            }
        };

        // 创建新会话
        const createSession = async () => {
            try {
                const session = await api.createSession();
                currentSessionId.value = session.sessionId;
                store.setSession(session.sessionId);
                await loadSessions();
                await loadHistory();
            } catch (e) {
                alert('创建会话失败: ' + e.message);
            }
        };

        // 切换会话
        const switchSession = async () => {
            store.setSession(currentSessionId.value);
            if (currentSessionId.value) {
                await loadHistory();
            } else {
                messages.value = [];
            }
        };

        // 加载历史消息
        const loadHistory = async () => {
            if (!currentSessionId.value) return;
            try {
                const history = await api.getHistory(currentSessionId.value);
                messages.value = history || [];
                scrollToBottom();
            } catch (e) {
                console.error('加载历史消息失败:', e);
            }
        };

        // 发送消息
        const send = async () => {
            if (!currentSessionId.value) {
                alert('请先创建会话');
                return;
            }
            const text = inputText.value.trim();
            if (!text && pendingFiles.value.length === 0) return;

            sending.value = true;
            inputText.value = '';

            // 先上传文件
            let uploadedFiles = [];
            if (pendingFiles.value.length > 0) {
                for (const file of pendingFiles.value) {
                    try {
                        const result = await api.uploadFile(currentSessionId.value, file);
                        uploadedFiles.push({ name: file.name, path: result });
                    } catch (e) {
                        console.error('上传文件失败:', e);
                    }
                }
                pendingFiles.value = [];
            }

            // 构造消息
            let fullMessage = text;
            if (uploadedFiles.length > 0) {
                const fileList = uploadedFiles.map(f => `📎 ${f.name}`).join('\n');
                fullMessage = (text ? text + '\n\n' : '') + '【上传的文件】\n' + fileList;
            }

            // 显示用户消息
            messages.value.push({ role: 'user', content: fullMessage, timestamp: Date.now() });
            scrollToBottom();

            try {
                const response = await api.sendMessage(currentSessionId.value, fullMessage);
                messages.value.push({
                    role: 'assistant',
                    content: response.content || '（无响应）',
                    timestamp: Date.now()
                });
            } catch (e) {
                messages.value.push({
                    role: 'assistant',
                    content: '错误: ' + e.message,
                    timestamp: Date.now()
                });
            } finally {
                sending.value = false;
                scrollToBottom();
            }
        };

        // 渲染内容
        const renderContent = (msg) => {
            if (msg.role === 'assistant') {
                return marked.parse(msg.content || '');
            }
            return escapeHtml(msg.content || '');
        };

        // 滚动到底部
        const scrollToBottom = () => {
            Vue.nextTick(() => {
                if (messagesEl.value) {
                    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
                }
            });
        };

        // 复制会话 ID
        const copyId = () => {
            if (currentSessionId.value) {
                navigator.clipboard.writeText(currentSessionId.value);
                copied.value = true;
                setTimeout(() => copied.value = false, 1500);
            }
        };

        // 文件选择
        const handleFileSelect = (event) => {
            const files = Array.from(event.target.files);
            files.forEach(file => {
                if (!pendingFiles.value.some(f => f.name === file.name)) {
                    pendingFiles.value.push(file);
                }
            });
            event.target.value = '';
        };

        // 移除文件
        const removeFile = (index) => {
            pendingFiles.value.splice(index, 1);
        };

        // 获取文件图标
        const getFileIcon = (filename) => {
            const ext = filename.split('.').pop().toLowerCase();
            const icons = {
                pdf: '📄', xlsx: '📊', xls: '📊', csv: '📋',
                doc: '📝', docx: '📝', txt: '📃', md: '📃',
                zip: '📦', rar: '📦', '7z': '📦',
                png: '🖼️', jpg: '🖼️', jpeg: '🖼️', gif: '🖼️',
                html: '🌐', css: '🎨', js: '📜', py: '🐍'
            };
            return icons[ext] || '📁';
        };

        // HTML 转义
        const escapeHtml = (str) => {
            if (!str) return '';
            return str.replace(/&/g, '&amp;')
                      .replace(/</g, '&lt;')
                      .replace(/>/g, '&gt;')
                      .replace(/"/g, '&quot;');
        };

        // VNC 相关方法
        const toggleVnc = () => {
            vncOpen.value = !vncOpen.value;
            if (vncOpen.value && currentSessionId.value && !vncUrl.value) {
                loadVncView();
            }
        };

        const loadVncView = async () => {
            if (!currentSessionId.value) {
                vncPlaceholder.value = '请先创建会话';
                return;
            }
            vncStatus.value = '连接中...';
            vncPlaceholder.value = '正在获取沙箱地址...';
            try {
                const endpoint = await api.getAioEndpoint(currentSessionId.value);
                if (endpoint) {
                    vncUrl.value = `http://${endpoint}/`;
                    vncStatus.value = '已连接';
                } else {
                    vncPlaceholder.value = '无法获取沙箱地址';
                    vncStatus.value = '连接失败';
                }
            } catch (e) {
                vncPlaceholder.value = '连接错误: ' + e.message;
                vncStatus.value = '连接失败';
            }
        };

        const resizeVnc = (delta) => {
            vncWidth.value = Math.max(30, Math.min(90, vncWidth.value + delta));
        };

        const startResize = (e) => {
            isResizing = true;
            startX = e.clientX;
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';

            const onMouseMove = (e) => {
                if (!isResizing) return;
                const wrapper = document.querySelector('.chat-page');
                if (!wrapper) return;
                const wrapperWidth = wrapper.offsetWidth;
                const diff = startX - e.clientX;
                const currentWidthPx = (vncWidth.value / 100) * wrapperWidth;
                const newWidthPx = currentWidthPx + diff;
                vncWidth.value = Math.max(20, Math.min(80, (newWidthPx / wrapperWidth) * 100));
                startX = e.clientX;
            };

            const onMouseUp = () => {
                isResizing = false;
                document.body.style.cursor = '';
                document.body.style.userSelect = '';
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
            };

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        };

        // 监听会话变化，重置 VNC
        Vue.watch(currentSessionId, () => {
            vncUrl.value = '';
            vncStatus.value = '未连接';
            vncPlaceholder.value = '请先创建会话';
        });

        // 初始化
        Vue.onMounted(async () => {
            // 配置 marked
            marked.setOptions({
                highlight: function(code, lang) {
                    if (lang && hljs.getLanguage(lang)) {
                        return hljs.highlight(code, { language: lang }).value;
                    }
                    return hljs.highlightAuto(code).value;
                },
                breaks: true,
                gfm: true
            });

            await loadSessions();
            if (currentSessionId.value) {
                await loadHistory();
            }
        });

        return {
            store,
            messagesEl,
            sessions,
            currentSessionId,
            messages,
            inputText,
            sending,
            pendingFiles,
            copied,
            createSession,
            switchSession,
            send,
            renderContent,
            copyId,
            handleFileSelect,
            removeFile,
            getFileIcon,
            vncOpen,
            vncUrl,
            vncStatus,
            vncPlaceholder,
            vncWidth,
            toggleVnc,
            resizeVnc,
            startResize
        };
    }
};
