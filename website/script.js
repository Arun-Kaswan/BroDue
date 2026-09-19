document.addEventListener('DOMContentLoaded', () => {
  const year = document.getElementById('year');
  const yearMobile = document.getElementById('yearMobile');
  const y = String(new Date().getFullYear());
  if (year) year.textContent = y;
  if (yearMobile) yearMobile.textContent = y;

  const toggle = document.getElementById('navToggle');
  const menu = document.getElementById('mobileMenu');
  if (toggle && menu) {
    const close = () => {
      menu.classList.remove('open');
      toggle.setAttribute('aria-expanded', 'false');
      toggle.setAttribute('aria-label', 'Open menu');
    };
    toggle.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = menu.classList.contains('open');
      if (open) close();
      else {
        menu.classList.add('open');
        toggle.setAttribute('aria-expanded', 'true');
        toggle.setAttribute('aria-label', 'Close menu');
      }
    });
    menu.querySelectorAll('a').forEach((a) => a.addEventListener('click', close));
    document.addEventListener('click', (e) => {
      if (menu.classList.contains('open') && !menu.contains(e.target) && e.target !== toggle && !toggle.contains(e.target)) close();
    });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
    window.addEventListener('resize', () => { if (window.innerWidth > 895) close(); });
  }

  const header = document.getElementById('pillHeader');
  if (header) {
    let scrollEndTimer = null;
    let ticking = false;
    let lastY = window.scrollY;
    let touching = false;
    const mobMenu = document.getElementById('mobileMenu');
    const animTargets = () => [header, mobMenu].filter(Boolean);
    const clearScrollState = () => {
      animTargets().forEach((el) => el.classList.remove('is-scrolling-down', 'is-scrolling-up'));
    };
    const animQuery = window.matchMedia('(max-width: 1024px)');
    animQuery.addEventListener('change', () => {
      if (!animQuery.matches) clearScrollState();
    });
    const scheduleRevert = () => {
      clearTimeout(scrollEndTimer);
      scrollEndTimer = setTimeout(() => {
        if (!touching) clearScrollState();
      }, 300);
    };
    const onScrollFrame = () => {
      ticking = false;
      if (!animQuery.matches) {
        clearScrollState();
        return;
      }
      const y = window.scrollY;
      if (y > lastY) {
        animTargets().forEach((el) => {
          el.classList.remove('is-scrolling-up');
          el.classList.add('is-scrolling-down');
        });
      } else if (y < lastY) {
        animTargets().forEach((el) => {
          el.classList.remove('is-scrolling-down');
          el.classList.add('is-scrolling-up');
        });
      }
      lastY = y;
      scheduleRevert();
    };
    window.addEventListener('scroll', () => {
      if (!ticking) {
        ticking = true;
        requestAnimationFrame(onScrollFrame);
      }
    }, { passive: true });
    window.addEventListener('touchstart', () => {
      touching = true;
      clearTimeout(scrollEndTimer);
    }, { passive: true });
    const onTouchEnd = () => {
      touching = false;
      scheduleRevert();
    };
    window.addEventListener('touchend', onTouchEnd, { passive: true });
    window.addEventListener('touchcancel', onTouchEnd, { passive: true });
  }

  document.querySelectorAll('a[href^="#"]').forEach((a) => {
    a.addEventListener('click', (e) => {
      const id = a.getAttribute('href');
      if (!id || id === '#') return;
      const target = document.querySelector(id);
      if (!target) return;
      e.preventDefault();
      const top = target.getBoundingClientRect().top + window.scrollY;
      window.scrollTo({ top, behavior: 'smooth' });
    });
  });

  if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    const els = document.querySelectorAll('.reveal, .section-head, .strip-inner');
    els.forEach((el) => el.classList.add('reveal'));
    const io = new IntersectionObserver((entries) => {
      entries.forEach((en) => {
        if (en.isIntersecting) { en.target.classList.add('is-visible'); io.unobserve(en.target); }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    document.querySelectorAll('.reveal').forEach((el) => io.observe(el));
  } else {
    document.querySelectorAll('.reveal').forEach((el) => el.classList.add('is-visible'));
  }

  const REPO = 'Arun-Kaswan/BroDue';
  let apkUrlPromise = null;
  const resolveApkUrl = () => {
    if (!apkUrlPromise) {
      apkUrlPromise = fetch('https://api.github.com/repos/' + REPO + '/releases/latest')
        .then((r) => {
          if (!r.ok) throw new Error('lookup failed');
          return r.json();
        })
        .then((rel) => {
          const apk = (rel.assets || []).find((a) => /\.apk$/i.test(a.name));
          if (!apk) throw new Error('no apk');
          return apk.browser_download_url;
        })
        .catch(() => null);
    }
    return apkUrlPromise;
  };
  if ('requestIdleCallback' in window) requestIdleCallback(() => resolveApkUrl());
  else setTimeout(resolveApkUrl, 2000);
  document.querySelectorAll('a[data-download-apk]').forEach((a) => {
    a.addEventListener('click', (e) => {
      e.preventDefault();
      if (a.dataset.busy) return;
      a.dataset.busy = '1';
      resolveApkUrl().then((url) => {
        delete a.dataset.busy;
        if (url) {
          const tmp = document.createElement('a');
          tmp.href = url;
          tmp.rel = 'noopener';
          document.body.appendChild(tmp);
          tmp.click();
          tmp.remove();
        } else {
          window.open(a.href, '_blank', 'noopener');
        }
      });
    });
  });
});
