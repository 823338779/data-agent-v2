import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthenticatedApp } from './AuthenticatedApp';
import './styles.css';
import './styles/shell.css';
import './styles/conversation.css';
import './styles/history.css';
import './styles/tools.css';
import './auth.css';
import './scheduled-tasks.css';
import './metric-platform.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthenticatedApp />
  </React.StrictMode>,
);
