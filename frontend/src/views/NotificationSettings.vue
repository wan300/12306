<template>
  <div class="notification-settings-page">
    <el-card shadow="never" class="main-card">
        <template #header>
            <div class="card-header">
                <div class="header-title">
                    <el-icon class="header-icon"><Bell /></el-icon>
                    <span>配置选项</span>
                </div>
                <div>
                     <el-button type="primary" :loading="saving" @click="handleSave">保存配置</el-button>
                     <el-button type="success" :loading="testing" @click="handleTest">测试发送</el-button>
                </div>
            </div>
        </template>
        
        <div class="settings-container">
            <div class="settings-sidebar">
                <el-scrollbar>
                    <div class="menu-list">
                        <div 
                            v-for="(def, key) in CHANNEL_DEFS" 
                            :key="key"
                            class="menu-item"
                            :class="{ active: currentType === key }"
                            @click="switchType(key)"
                        >
                            <div class="menu-item-icon" :class="key">
                                <el-icon><component :is="getIcon(key)" /></el-icon>
                            </div>
                            <span class="menu-item-text">{{ def.name }}</span>
                            <div v-if="isChannelEnabled(key)" class="status-dot success"></div>
                        </div>
                    </div>
                </el-scrollbar>
            </div>

            <div class="settings-content">
                <div class="content-header">
                    <h2>{{ CHANNEL_DEFS[currentType]?.name }}</h2>
                    <div class="enable-switch">
                        <span class="label">是否启用</span>
                         <el-switch 
                            v-model="currentForm.enable" 
                            inline-prompt
                            active-text="开"
                            inactive-text="关"
                        />
                    </div>
                </div>

                <div class="form-scroll-area">
                    <el-form layout="vertical" label-position="top" :model="currentForm" ref="formRef" class="setting-form">
                        <el-form-item label="备注名称">
                            <el-input v-model="currentForm.name" placeholder="自定义名称" />
                        </el-form-item>

                        <template v-if="fieldDefinitions[currentType]">
                            <el-form-item 
                                v-for="field in fieldDefinitions[currentType]" 
                                :key="field.key"
                                :label="field.label"
                            >
                                <el-input 
                                    v-if="!field.type || field.type === 'text'"
                                    v-model="currentForm[field.key]"
                                    :placeholder="field.placeholder"
                                    clearable
                                />

                                <el-input 
                                    v-else-if="field.type === 'password'"
                                    v-model="currentForm[field.key]"
                                    :placeholder="field.placeholder"
                                    type="password"
                                    show-password
                                    clearable
                                />

                                <el-input 
                                    v-else-if="field.type === 'textarea'"
                                    v-model="currentForm[field.key]"
                                    :placeholder="field.placeholder"
                                    type="textarea"
                                    :rows="4"
                                />
                                
                                <el-select 
                                    v-else-if="field.type === 'select'"
                                    v-model="currentForm[field.key]"
                                    :placeholder="field.placeholder"
                                    class="w-full"
                                >
                                    <el-option
                                        v-for="opt in field.options"
                                        :key="opt.value"
                                        :label="opt.label"
                                        :value="opt.value"
                                    />
                                </el-select>

                                <div v-if="field.help" class="form-help-text">{{ field.help }}</div>
                            </el-form-item>
                        </template>
                        
                         <el-alert
                            v-if="!fieldDefinitions[currentType] || fieldDefinitions[currentType].length === 0"
                            title="该渠道无需额外配置"
                            type="info"
                            :closable="false"
                            show-icon
                         />
                    </el-form>
                </div>
            </div>
        </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { 
    Bell, Iphone, ChatDotRound, Message, Monitor, ChatLineRound, Link as LinkIcon
} from '@element-plus/icons-vue'
import api from '../api'

const currentType = ref('webhook')
const saving = ref(false)
const testing = ref(false)

const fullConfig = ref({})

const currentForm = ref({
    name: '',
    enable: false
})

const CHANNEL_DEFS = {
    'webhook': { key: 'WEBHOOK_URL', enableKey: 'ENABLE_WEBHOOK', name: '自定义 Webhook' },
    'wecom_app': { key: 'QYWX_AM', enableKey: 'ENABLE_WECOM_APP', name: '企微应用' },
    'wecom_bot': { key: 'QYWX_KEY', enableKey: 'ENABLE_WECOM_BOT', name: '企微机器人' },
    'dingding': { key: 'DD_BOT_TOKEN', enableKey: 'ENABLE_DINGDING', name: '钉钉' },
    'feishu': { key: 'FSKEY', enableKey: 'ENABLE_FEISHU', name: '飞书' },
    'smtp': { key: 'SMTP_SERVER', enableKey: 'ENABLE_SMTP', name: '邮件 (SMTP)' },
    'pushplus': { key: 'PUSH_PLUS_TOKEN', enableKey: 'ENABLE_PUSHPLUS', name: 'PushPlus' },
    'serverj': { key: 'PUSH_KEY', enableKey: 'ENABLE_SERVERJ', name: 'Server酱' },
    'bark': { key: 'BARK_PUSH', enableKey: 'ENABLE_BARK', name: 'Bark' },
}

const fieldDefinitions = {
    'bark': [
        { key: 'BARK_PUSH', label: 'Bark Key / URL', placeholder: '形如 xxxx 或 https://...' },
        { key: 'BARK_SOUND', label: '铃声 (Sound)', placeholder: '例如: minuet' },
        { key: 'BARK_GROUP', label: '分组 (Group)', placeholder: '默认: 12306' },
        { key: 'BARK_ICON', label: '图标 (Icon)', placeholder: '图标 URL' },
    ],
    'dingding': [
        { key: 'DD_BOT_TOKEN', label: 'Token', placeholder: '机器人 Token' },
        { key: 'DD_BOT_SECRET', label: 'Secret', placeholder: '加签 Secret', type: 'password' },
    ],
    'wecom_bot': [
        { key: 'QYWX_KEY', label: 'Key', placeholder: '机器人的 Key' },
    ],
    'wecom_app': [
        { key: 'QYWX_AM_corpid', label: '企业ID (corpid)', placeholder: '企业ID' },
        { key: 'QYWX_AM_corpsecret', label: '应用Secret (corpsecret)', type: 'password', placeholder: '应用Secret' },
        { key: 'QYWX_AM_touser', label: '接收者 (touser 或 @all)', placeholder: '接收者' },
        { key: 'QYWX_AM_agentid', label: '应用AgentID (agentid)', placeholder: '应用AgentID' },
        { key: 'QYWX_AM_media_id', label: '图文素材 media_id (可选)', placeholder: '图文素材media_id' },
        { key: 'QYWX_ORIGIN', label: '转发代理地址 (可选)', placeholder: 'https://qyapi.weixin.qq.com', help: '说明：企业微信消息的转发代理地址，2022年6月20日后创建的自建应用才需要，其他情况请保持默认' },
    ],
    'serverj': [
        { key: 'PUSH_KEY', label: 'SendKey', placeholder: 'Server酱 SendKey' },
    ],
    'pushplus': [
        { key: 'PUSH_PLUS_TOKEN', label: 'Token', placeholder: 'PushPlus Token' },
        { key: 'PUSH_PLUS_USER', label: '群组编码', placeholder: '一对多推送时填写' },
    ],
    'smtp': [
        { key: 'SMTP_SERVER', label: 'SMTP 服务器', placeholder: '例如: smtp.qq.com:465' },
        { key: 'SMTP_EMAIL', label: '邮箱账号', placeholder: 'your_email@qq.com' },
        { key: 'SMTP_PASSWORD', label: '邮箱密码/授权码', type: 'password' },
        { key: 'SMTP_NAME', label: '发送人名称', placeholder: '12306助手' },
        { 
            key: 'SMTP_SSL', label: '是否使用 SSL', type: 'select', 
            options: [{label: '是', value: 'true'}, {label: '否', value: 'false'}]
        },
    ],
    'feishu': [
        { key: 'FSKEY', label: '飞书 Key', placeholder: 'Webhook 地址最后的 Key' },
    ],
    'webhook': [
        { key: 'WEBHOOK_URL', label: 'Webhook URL', placeholder: 'http://... (可包含 $title, $content)', help: 'URL中可使用 $title 和 $content 变量' },
        { key: 'WEBHOOK_METHOD', label: '请求方法', type: 'select', options: [{label: 'GET', value: 'GET'}, {label: 'POST', value: 'POST'}] },
        { 
            key: 'WEBHOOK_CONTENT_TYPE', label: 'Content-Type', type: 'select', 
            options: [
                {label: 'application/json', value: 'application/json'},
                {label: 'application/x-www-form-urlencoded', value: 'application/x-www-form-urlencoded'},
                {label: 'multipart/form-data', value: 'multipart/form-data'},
                {label: 'text/plain', value: 'text/plain'}
            ],
            placeholder: '默认 application/json'
        },
        { key: 'WEBHOOK_HEADERS', label: 'Header (JSON)', type: 'textarea', placeholder: '{"Authorization": "Bearer ..."}' },
        { key: 'WEBHOOK_BODY', label: 'Body (JSON)', type: 'textarea', placeholder: '{"msg": "$content"}', help: '支持变量: $title (标题), $content (内容)。GET请求通常无需填写。' },
    ]
}

const loadConfig = async () => {
    try {
        const res = await api.getNotificationConfig()
        fullConfig.value = res || {}
        syncForm()
    } catch (error) {
        ElMessage.error('加载配置失败: ' + error.message)
    }
}

const syncForm = () => {
    const type = currentType.value
    const def = CHANNEL_DEFS[type]
    
    currentForm.value = {
        name: fullConfig.value[`${type}_NAME`] || '',
        enable: fullConfig.value[def.enableKey] === 'true' || fullConfig.value[def.enableKey] === true
    }

    if (type === 'wecom_app') {
        // Special parsing for WeCom App
        const amVal = fullConfig.value['QYWX_AM'] || ''
        const parts = amVal.split(',')
        currentForm.value['QYWX_AM_corpid'] = parts[0] || ''
        currentForm.value['QYWX_AM_corpsecret'] = parts[1] || ''
        currentForm.value['QYWX_AM_touser'] = parts[2] || ''
        currentForm.value['QYWX_AM_agentid'] = parts[3] || ''
        currentForm.value['QYWX_AM_media_id'] = parts[4] || ''
        currentForm.value['QYWX_ORIGIN'] = fullConfig.value['QYWX_ORIGIN'] || ''
    } else {
        const fields = fieldDefinitions[type] || []
        fields.forEach(f => {
            currentForm.value[f.key] = fullConfig.value[f.key] || ''
        })
    }
}

const switchType = (type) => {
    currentType.value = type
    syncForm()
}

const handleSave = async () => {
    saving.value = true
    try {
        const type = currentType.value
        const def = CHANNEL_DEFS[type]
        const newConfig = { ...fullConfig.value }

        newConfig[def.enableKey] = currentForm.value.enable
        newConfig[`${type}_NAME`] = currentForm.value.name

        if (type === 'wecom_app') {
            // Reconstruct logic for WeCom
            const { QYWX_AM_corpid, QYWX_AM_corpsecret, QYWX_AM_touser, QYWX_AM_agentid, QYWX_AM_media_id } = currentForm.value
            // 确保没有 undefined
            const safe = (v) => v || ''
            const amVal = [
                safe(QYWX_AM_corpid), 
                safe(QYWX_AM_corpsecret), 
                safe(QYWX_AM_touser), 
                safe(QYWX_AM_agentid), 
                safe(QYWX_AM_media_id)
            ].join(',')
            newConfig['QYWX_AM'] = amVal
            newConfig['QYWX_ORIGIN'] = currentForm.value['QYWX_ORIGIN'] || ''
        } else {
            const fields = fieldDefinitions[type] || []
            fields.forEach(f => {
                newConfig[f.key] = currentForm.value[f.key] || ''
            })
        }

        await api.updateNotificationConfig(newConfig)
        fullConfig.value = newConfig 
        ElMessage.success('保存成功')
    } catch (error) {
        ElMessage.error('保存失败: ' + error.message)
    } finally {
        saving.value = false
    }
}

const handleTest = async () => {
    testing.value = true
    try {
        const testConfig = { ...fullConfig.value }
        
        const type = currentType.value
        const def = CHANNEL_DEFS[type]
        
        testConfig[def.enableKey] = true 
        testConfig[`${type}_NAME`] = currentForm.value.name
        
        if (type === 'wecom_app') {
            const { QYWX_AM_corpid, QYWX_AM_corpsecret, QYWX_AM_touser, QYWX_AM_agentid, QYWX_AM_media_id } = currentForm.value
            const safe = (v) => v || ''
            const amVal = [
                safe(QYWX_AM_corpid), 
                safe(QYWX_AM_corpsecret), 
                safe(QYWX_AM_touser), 
                safe(QYWX_AM_agentid), 
                safe(QYWX_AM_media_id)
            ].join(',')
            testConfig['QYWX_AM'] = amVal
            testConfig['QYWX_ORIGIN'] = currentForm.value['QYWX_ORIGIN'] || ''
        } else {
            const fields = fieldDefinitions[type] || []
            fields.forEach(f => {
                testConfig[f.key] = currentForm.value[f.key] || ''
            })
        }

        // Disable others
        for (const k in CHANNEL_DEFS) {
             if (k !== type) {
                 const d = CHANNEL_DEFS[k]
                 testConfig[d.enableKey] = false
             }
        }
        
        await api.testNotification(testConfig)
        ElMessage.success('测试消息已发送')
    } catch (e) {
        ElMessage.error('测试失败: ' + e.message)
    } finally {
        testing.value = false
    }
}

const isChannelEnabled = (type) => {
    const def = CHANNEL_DEFS[type]
    const val = fullConfig.value[def.enableKey]
    return val === 'true' || val === true
}

const getIcon = (type) => {
    switch(type) {
        case 'webhook': return LinkIcon
        case 'bark': return Iphone
        case 'dingding': return ChatLineRound
        case 'wecom_bot': 
        case 'wecom_app': return ChatDotRound
        case 'smtp': return Message
        default: return Monitor
    }
}

onMounted(() => {
    loadConfig()
})
</script>

<style scoped>
/* Base Styles */
.notification-settings-page {
    padding: 0;
    height: calc(100vh - 92px);
    overflow: hidden;
    box-sizing: border-box;
}

.main-card {
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: 0;
    --el-card-padding: 0; 
}
/* Override el-card body to flex */
:deep(.el-card__body) {
    flex: 1;
    overflow: hidden;
    padding: 0;
    display: flex;
    flex-direction: column;
}

.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 24px;
    border-bottom: 1px solid #e4e7ed;
}

.header-title {
    display: flex;
    align-items: center;
    font-size: 18px;
    font-weight: 600;
    color: #303133;
}

.header-icon {
    margin-right: 12px;
    color: #409EFF;
    font-size: 20px;
}

.settings-container {
    display: flex;
    flex: 1;
    overflow: hidden;
}

/* Sidebar */
.settings-sidebar {
    width: 240px;
    background-color: #f8fafc;
    border-right: 1px solid #e4e7ed;
    display: flex;
    flex-direction: column;
}

.menu-list {
    padding: 12px;
}

.menu-item {
    display: flex;
    align-items: center;
    padding: 12px 16px;
    margin-bottom: 4px;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
    color: #606266;
    position: relative;
}

.menu-item:hover {
    background-color: #eef2f8;
}

.menu-item.active {
    background-color: #fff;
    color: #409eff;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}

.menu-item-icon {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    background: #e4e7ed;
    color: #909399;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
    margin-right: 12px;
    transition: all 0.2s;
}

.menu-item.active .menu-item-icon {
    background: #ecf5ff;
    color: #409eff;
}

.menu-item-text {
    font-weight: 500;
    flex: 1;
}

.status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background-color: #dcdfe6;
}
.status-dot.success {
    background-color: #67c23a;
    box-shadow: 0 0 4px #67c23a;
}

/* Content */
.settings-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    background-color: #fff;
    min-width: 0;
}

.content-header {
    padding: 24px 32px 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #f2f3f5;
}

.content-header h2 {
    margin: 0;
    font-size: 20px;
    color: #303133;
}

.enable-switch {
    display: flex;
    align-items: center;
    gap: 8px;
}
.enable-switch .label {
    font-size: 14px;
    color: #606266;
}

.form-scroll-area {
    flex: 1;
    overflow-y: auto;
    padding: 24px 32px;
}

.setting-form {
    max-width: 600px;
}

.w-full { width: 100%; }
.form-help-text {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
    line-height: 1.4;
}

/* ---------------- Mobile Responsive ---------------- */
@media (max-width: 768px) {
    .notification-settings-page {
        height: auto !important;
        overflow: visible !important;
        padding-bottom: 20px;
    }
    
    .main-card {
        height: auto !important;
    }
    
    :deep(.el-card__body) {
        height: auto !important;
        overflow: visible !important;
        display: block !important;
    }

    .card-header {
        padding: 12px 16px;
        flex-direction: column;
        align-items: flex-start;
        gap: 12px;
    }

    .header-title {
        font-size: 16px;
        margin-bottom: 0;
    }
    
    .card-header > div:last-child {
        width: 100%;
        display: flex;
        gap: 8px;
    }
    
    .card-header .el-button {
        flex: 1;
    }

    .settings-container {
        flex-direction: column;
        height: auto !important;
        overflow: visible !important;
    }

    .settings-sidebar {
        width: 100%;
        height: auto;
        border-right: none;
        border-bottom: 1px solid #e4e7ed;
        flex-shrink: 0;
        position: sticky;
        top: 0;
        z-index: 10;
    }
    
    .settings-sidebar .el-scrollbar {
        height: auto;
    }

    .menu-list {
        display: flex;
        padding: 8px 12px;
        gap: 8px;
        overflow-x: auto;
    }

    .menu-item {
        flex-direction: column;
        align-items: center;
        padding: 8px;
        margin-bottom: 0;
        min-width: 64px;
        text-align: center;
    }
    
    .menu-item-icon {
        margin-right: 0;
        margin-bottom: 4px;
    }

    .menu-item-text {
        font-size: 12px;
        width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    
    .status-dot {
        position: absolute;
        top: 6px;
        right: 6px;
    }

    .settings-content {
        height: auto !important;
        overflow: visible !important;
    }

    .content-header {
        padding: 16px;
        flex-direction: row; 
        position: sticky;
        top: 0;
        background: #fff;
        z-index: 5;
    }
    
    .content-header h2 {
        font-size: 16px;
    }

    .form-scroll-area {
        height: auto !important;
        overflow: visible !important;
        padding: 16px;
    }
}
</style>
