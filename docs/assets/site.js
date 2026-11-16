/* WeTube landing page — dependency-free behaviour layer.
   Nav state, mobile menu, scroll reveals, card glow and copy buttons. */
(function () {
  "use strict";

  var root = document.documentElement;
  var nav = document.getElementById("nav");
  var menuBtn = document.getElementById("menuBtn");
  var navLinks = document.getElementById("navLinks");
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* --- Nav: raise the bar once the page moves --- */
  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function () {
      nav.classList.toggle("scrolled", window.scrollY > 8);
      ticking = false;
    });
  }
  if (nav) {
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  /* --- Mobile menu --- */
  function setMenu(open) {
    if (!nav || !menuBtn) return;
    nav.setAttribute("data-open", open ? "true" : "false");
    menuBtn.setAttribute("aria-expanded", open ? "true" : "false");
  }
  if (menuBtn) {
    menuBtn.addEventListener("click", function () {
      setMenu(nav.getAttribute("data-open") !== "true");
    });
  }
  if (navLinks) {
    navLinks.addEventListener("click", function (event) {
      if (event.target.closest("a")) setMenu(false);
    });
  }
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") setMenu(false);
  });
  window.addEventListener("resize", function () {
    if (window.innerWidth > 860) setMenu(false);
  });

  /* --- Reveal on scroll --- */
  var revealables = Array.prototype.slice.call(document.querySelectorAll(".reveal"));
  if (!("IntersectionObserver" in window) || reduceMotion) {
    revealables.forEach(function (el) { el.classList.add("in"); });
  } else {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          observer.unobserve(entry.target);
        }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });
    revealables.forEach(function (el) { observer.observe(el); });
  }

  /* --- Pointer glow on cards --- */
  var glowCards = Array.prototype.slice.call(document.querySelectorAll(".card, .privacy-card"));
  glowCards.forEach(function (card) {
    card.addEventListener("pointermove", function (event) {
      if (event.pointerType === "touch") return;
      var rect = card.getBoundingClientRect();
      card.style.setProperty("--mx", (event.clientX - rect.left) + "px");
      card.style.setProperty("--my", (event.clientY - rect.top) + "px");
    });
  });

  /* --- Copy buttons: <button data-copy="#selector"> --- */
  function flash(button, ok) {
    var label = button.getAttribute("data-label") || button.textContent;
    if (!button.hasAttribute("data-label")) button.setAttribute("data-label", label);
    button.textContent = ok ? "Copied" : "Press Ctrl+C";
    button.classList.toggle("done", ok);
    window.clearTimeout(button._t);
    button._t = window.setTimeout(function () {
      button.textContent = label;
      button.classList.remove("done");
    }, 1600);
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var field = document.createElement("textarea");
      field.value = text;
      field.setAttribute("readonly", "");
      field.style.position = "fixed";
      field.style.top = "-1000px";
      document.body.appendChild(field);
      field.select();
      try {
        document.execCommand("copy") ? resolve() : reject(new Error("copy failed"));
      } catch (err) {
        reject(err);
      } finally {
        document.body.removeChild(field);
      }
    });
  }

  Array.prototype.slice.call(document.querySelectorAll("[data-copy]")).forEach(function (button) {
    button.addEventListener("click", function () {
      var target = document.querySelector(button.getAttribute("data-copy"));
      if (!target) return;
      var text = (target.getAttribute("data-copy-text") || target.textContent).trim();
      copyText(text).then(
        function () { flash(button, true); },
        function () { flash(button, false); }
      );
    });
  });

  /* --- Footer year --- */
  var year = document.getElementById("year");
  if (year) year.textContent = String(new Date().getFullYear());

  root.classList.add("ready");
})();
