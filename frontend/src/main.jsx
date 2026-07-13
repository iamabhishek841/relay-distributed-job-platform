import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import ExperienceGuide from './ExperienceGuide.jsx';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ExperienceGuide />
    <App />
  </React.StrictMode>
);
