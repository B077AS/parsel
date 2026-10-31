import { createApp } from 'vue';
import App from './App.vue';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import { initTheme } from './theme';

// Apply the saved theme before mounting so the first paint already has the right colours.
initTheme();
createApp(App).mount('#app');
