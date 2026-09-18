// The tenth shadcn base component, copied in the way the other nine were: unmodified.
//
// IT IS `dropdown-menu.tsx`'s SIBLING AND NOT A REPLACEMENT FOR IT. A menu is a list
// of things to pick; this is a box of things to read (the context panel), and the two
// differ in the keyboard and dismissal behaviour a reader expects -- which is exactly
// what this repo copies the base components for rather than hand-rolling. The
// primitive comes from `radix-ui`, the same package `tooltip.tsx` and `dialog.tsx`
// take it from.
//
// WHAT IS NOT HERE: an arrow, a scroll area, and any styling decision made for the
// panel using it -- the panel passes its own width and padding, like every other
// caller of these components.
import * as React from "react"
import { cn } from "cn"
import { Popover as PopoverPrimitive } from "radix-ui"

function Popover({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />
}

function PopoverTrigger({
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Trigger>) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />
}

function PopoverContent({
  className,
  align = "center",
  sideOffset = 4,
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Content>) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(
          "bg-popover text-popover-foreground ring-foreground/10 z-50 w-72 origin-(--radix-popover-content-transform-origin) rounded-md p-3 text-sm ring-1 outline-hidden data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
          className
        )}
        {...props}
      />
    </PopoverPrimitive.Portal>
  )
}

export { Popover, PopoverContent, PopoverTrigger }
