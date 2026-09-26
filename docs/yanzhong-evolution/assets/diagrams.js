(function () {
  if (typeof mermaid === 'undefined') return;
  mermaid.initialize({
    startOnLoad: true,
    theme: 'base',
    securityLevel: 'strict',
    themeVariables: {
      primaryColor: '#EAF6F1',
      primaryTextColor: '#1E2B27',
      primaryBorderColor: '#12B886',
      lineColor: '#64736E',
      secondaryColor: '#ECF4F1',
      tertiaryColor: '#F6FAF8',
      clusterBkg: '#F6FAF8',
      clusterBorder: '#D8E4E0',
      edgeLabelBackground: '#FFFFFF',
      fontSize: '14px',
      fontFamily: "'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif"
    },
    flowchart: { curve: 'basis', nodeSpacing: 42, rankSpacing: 54, padding: 10 }
  });
})();
