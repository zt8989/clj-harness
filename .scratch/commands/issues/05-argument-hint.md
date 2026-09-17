# 05 — `argument-hint` 进列表并指导输入

**What to build:** 作者在 frontmatter 里写一行 `argument-hint`，人在菜单里就看得见这条命令
想要什么参数。没写的命令不显示 hint。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `argument-hint` 不再是「读到也不报错」的宿主字段：它出现在 `GET /api/picks` 里**命令那些行**上
      （技能那些行没有这个字段，它们本来就不收参数）。
- [ ] 菜单那一行显示 `名字 <hint>`；hint 只当**占位提示**，不自动填进输入框——选中后写进去的
      仍是 `/名字 `，后面由人写。
- [ ] **hint 是可选的**：没有 frontmatter 的命令、以及有 frontmatter 但没写 `argument-hint` 的命令，
      那一行都不显示 hint，也不显示空的一对尖括号。两种缺失是同一种表现。
- [ ] `argument-hint` 是**给人看的字符串**：不参与解析、不校验参数个数、不决定 `$n` 的边界。
      它是提示，不是契约——契约是正文里写了哪个占位符。
