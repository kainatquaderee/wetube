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

  /* --- GitHub star count: one request, cached, the page never waits for it --- */
 var starCount = document.querySelector("[data-star-count]");
 if (starCount) {
   var starLink = starCount.closest("[data-star-repo]");
   var starRepo = starLink ? starLink.getAttribute("data-star-repo") : "";
   var starSep = document.querySelector("[data-star-sep]");
   var starKey = "wetube:stars:" + starRepo;
   var starTtl = 6 * 60 * 60 * 1000;

   var paintStars = function (count) {
     if (typeof count !== "number" || !isFinite(count)) return;
     starCount.textContent = count >= 1000
       ? (count >= 100000 ? Math.round(count / 1000) : Math.round(count / 100) / 10) + "k"
       : String(count);
     starCount.hidden = false;
     if (starSep) starSep.hidden = false;
   };

   var cachedStars = null;
   try {
     cachedStars = JSON.parse(window.localStorage.getItem(starKey) || "null");
   } catch (error) {
     cachedStars = null;
   }
   if (cachedStars && typeof cachedStars.count === "number" && Date.now() - cachedStars.at < starTtl) {
     paintStars(cachedStars.count);
   }

   if (starCount.hidden && starRepo && window.fetch) {
     window.fetch("https://api.github.com/repos/" + starRepo, {
       headers: { Accept: "application/vnd.github+json" }
     })
       .then(function (response) { return response.ok ? response.json() : null; })
       .then(function (data) {
         if (!data) return;
         paintStars(data.stargazers_count);
         try {
           window.localStorage.setItem(starKey, JSON.stringify({ count: data.stargazers_count, at: Date.now() }));
         } catch (error) {}
       })
       .catch(function () {});
   }
 }

root.classList.add("ready");
})();
