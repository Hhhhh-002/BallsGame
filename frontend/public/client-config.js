// 客户端网关配置：修改 IP、端口或路径后，刷新网页即可生效。
// 此文件优先于浏览器保存的地址和 VITE_GATEWAY_URL。
// gatewayUrl 留空时，使用浏览器保存的地址、环境变量或默认地址。
window.BALLS_CLIENT_CONFIG = {
  gatewayUrl: 'ws://127.0.0.1:9001/ws',
};
