"use client";

// LOCAL: this copied file is TRANSLATED IN PLACE. Upstream's own words -- the attach
// button's "Add Attachment" and the remove button's "Remove file", the "Upload
// failed" fallback, the tile's read-out, the preview's alt and dialog title, and the
// three type labels "Image" / "Document" / "File" -- are gone from this file and read
// from the `elements-files` catalog instead (spec decision 5, which reverses
// flat-step-rows decision 9's "leave the copies untouched"). WHAT DOES NOT MOVE is
// the boundary: the attachment's own name is data and passes through, and the type
// the runtime reports is only MAPPED to a word. The cost, written down: this file is
// no longer byte-comparable with upstream, so each deliberate edit below is marked
// `LOCAL:` -- a marker says "this was changed on purpose", not "this is what upstream
// changed". Every non-word byte -- `data-slot`, class names, upstream identifiers --
// is untouched.

import {
  type PropsWithChildren,
  useState,
  type FC,
  isValidElement,
} from "react";
import {
  XIcon,
  PlusIcon,
  FileText,
  Loader2Icon,
  AlertCircleIcon,
} from "lucide-react";
import {
  AttachmentPrimitive,
  ComposerPrimitive,
  MessagePrimitive,
  useAuiState,
  useAui,
} from "@assistant-ui/react";
import { useTranslation } from "react-i18next";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Avatar,
  AvatarImage,
  AvatarFallback,
} from "@/components/ui/avatar";
import { TooltipIconButton } from "@/components/assistant-ui/elements/tooltip-icon-button";
import { useAttachmentSrc } from "@/hooks/use-attachment-src";
import { cn } from "@/lib/utils";

type AttachmentPreviewProps = {
  src: string;
};

const AttachmentPreview: FC<AttachmentPreviewProps> = ({ src }) => {
  // LOCAL: upstream's "Attachment preview" alt is gone from this file and read from
  // the `elements-files` catalog instead (the tile below shows the same words).
  const { t } = useTranslation("elements-files");
  const [isLoaded, setIsLoaded] = useState(false);
  return (
    <img
      src={src}
      alt={t("attachment.previewAlt")}
      className={cn(
        "block h-auto max-h-[80vh] w-auto max-w-full rounded-sm object-contain transition-opacity duration-300 motion-reduce:transition-none",
        isLoaded
          ? "aui-attachment-preview-image-loaded opacity-100"
          : "aui-attachment-preview-image-loading opacity-0",
      )}
      onLoad={() => setIsLoaded(true)}
    />
  );
};

const AttachmentPreviewDialog: FC<PropsWithChildren> = ({ children }) => {
  const src = useAttachmentSrc();
  // LOCAL: upstream's dialog title, "Image Attachment Preview", is gone from this
  // file and read from the `elements-files` catalog instead. It is drawn only for a
  // screen reader (`sr-only`), and screen-reader text is copy like any other.
  const { t } = useTranslation("elements-files");

  if (!src) return children;

  return (
    <Dialog>
      <DialogTrigger
        className="aui-attachment-preview-trigger cursor-zoom-in"
        asChild
      >
        {isValidElement(children) ? (
          children
        ) : (
          <button type="button">{children}</button>
        )}
      </DialogTrigger>
      <DialogContent className="aui-attachment-preview-dialog-content [&>button]:bg-foreground/60 [&>button]:hover:bg-foreground/80 [&_svg]:text-background p-2 sm:max-w-3xl [&>button]:rounded-full [&>button]:p-1 [&>button]:opacity-100 [&>button]:ring-0!">
        <DialogTitle className="aui-sr-only sr-only">
          {t("attachment.previewTitle")}
        </DialogTitle>
        <div className="aui-attachment-preview bg-background relative mx-auto flex max-h-[80dvh] w-full items-center justify-center overflow-hidden rounded-sm">
          <AttachmentPreview src={src} />
        </div>
      </DialogContent>
    </Dialog>
  );
};

const AttachmentThumb: FC = () => {
  // LOCAL: the same "Attachment preview" alt as above, read from the catalog.
  const { t } = useTranslation("elements-files");
  const src = useAttachmentSrc();

  return (
    <Avatar className="aui-attachment-tile-avatar h-full w-full rounded-none">
      <AvatarImage
        src={src}
        alt={t("attachment.previewAlt")}
        className="aui-attachment-tile-image rounded-none object-cover"
      />
      <AvatarFallback>
        <FileText className="aui-attachment-tile-fallback-icon text-muted-foreground/80 size-6 stroke-[1.5]" />
      </AvatarFallback>
    </Avatar>
  );
};

const AttachmentUI: FC = () => {
  // LOCAL: upstream's three type labels -- "Image" / "Document" / "File" -- and its
  // "Upload failed" fallback are gone from this file and read from the
  // `elements-files` catalog instead. The type the runtime reports is only MAPPED to a
  // word, each branch below naming its own literal key; a type this file does not
  // know still passes through exactly as it came.
  const { t } = useTranslation("elements-files");
  const aui = useAui();
  const isComposer = aui.attachment.source !== "message";

  const isImage = useAuiState((s) => s.attachment.type === "image");
  const typeLabel = useAuiState((s) => {
    const type = s.attachment.type;
    switch (type) {
      case "image":
        return t("attachment.typeImage");
      case "document":
        return t("attachment.typeDocument");
      case "file":
        return t("attachment.typeFile");
      default:
        return type;
    }
  });

  const uploadState = useAuiState((s) =>
    s.attachment.status.type === "running"
      ? "uploading"
      : s.attachment.status.type === "incomplete" &&
          s.attachment.status.reason === "error"
        ? "error"
        : undefined,
  );
  const isUploading = uploadState === "uploading";
  const isError = uploadState === "error";

  const errorMessage = useAuiState((s) =>
    s.attachment.status.type === "incomplete" &&
    s.attachment.status.reason === "error"
      ? (s.attachment.status.message ?? t("attachment.uploadFailed"))
      : undefined,
  );

  // LOCAL: the tile's read-out used to be assembled from the type label and two
  // English suffixes (`", upload failed"` / `", uploading"`). The whole sentence is
  // the catalog's now, with the type and the state interpolated -- the state's suffix
  // carries its own punctuation, because Chinese does not join clauses the way
  // English does.
  return (
    <TooltipProvider>
      <Tooltip>
        <AttachmentPrimitive.Root
          className={cn(
            "aui-attachment-root relative",
            isComposer &&
              "animate-in fade-in-0 zoom-in-95 duration-200 motion-reduce:animate-none",
            isImage &&
              !isComposer &&
              "aui-attachment-root-message only:*:first:size-24",
          )}
        >
          <AttachmentPreviewDialog>
            <TooltipTrigger asChild>
              <div
                className={cn(
                  "aui-attachment-tile bg-muted hover:after:bg-foreground/10 focus-visible:ring-ring/50 relative size-14 cursor-pointer overflow-hidden rounded-[calc(var(--composer-radius,1.5rem)-var(--composer-padding,8px))] transition-transform outline-none after:pointer-events-none after:absolute after:inset-0 after:rounded-[inherit] after:ring-1 after:ring-black/10 after:transition-colors after:ring-inset focus-visible:ring-1 active:scale-[0.96] motion-reduce:transition-none dark:after:ring-white/10",
                  isError &&
                    "after:ring-destructive/60 dark:after:ring-destructive/60",
                )}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    e.currentTarget.click();
                  } else if (e.key === " ") {
                    e.preventDefault();
                  }
                }}
                onKeyUp={(e) => {
                  if (e.key === " ") e.currentTarget.click();
                }}
                aria-label={t("attachment.ariaLabel", {
                  type: typeLabel,
                  state: isError
                    ? t("attachment.ariaError")
                    : isUploading
                      ? t("attachment.ariaUploading")
                      : "",
                })}
              >
                <AttachmentThumb />
                {isUploading && (
                  <div
                    aria-hidden="true"
                    className="aui-attachment-tile-uploading bg-background/60 animate-in fade-in-0 absolute inset-0 flex items-center justify-center backdrop-blur-[2px] motion-reduce:animate-none"
                  >
                    <Loader2Icon className="text-muted-foreground size-4 animate-spin" />
                  </div>
                )}
                {isError && (
                  <div
                    aria-hidden="true"
                    className="aui-attachment-tile-error bg-background/70 animate-in fade-in-0 absolute inset-0 flex items-center justify-center backdrop-blur-[2px] motion-reduce:animate-none"
                  >
                    <AlertCircleIcon className="text-destructive size-4" />
                  </div>
                )}
              </div>
            </TooltipTrigger>
          </AttachmentPreviewDialog>
          {isComposer && <AttachmentRemove />}
        </AttachmentPrimitive.Root>
        <TooltipContent side="top">
          <AttachmentPrimitive.Name />
          {errorMessage && (
            <p className="aui-attachment-error-message">{errorMessage}</p>
          )}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
};

const AttachmentRemove: FC = () => {
  // LOCAL: upstream's "Remove file" tooltip is gone from this file and read from the
  // `elements-files` catalog instead.
  const { t } = useTranslation("elements-files");
  return (
    <AttachmentPrimitive.Remove asChild>
      <TooltipIconButton
        tooltip={t("attachment.remove")}
        className="aui-attachment-tile-remove absolute end-1 top-1 size-5 rounded-full bg-black/50! text-white after:absolute after:-inset-1.5 hover:bg-black/70! hover:text-white! active:scale-[0.96] motion-reduce:transition-none"
        side="top"
      >
        <XIcon className="aui-attachment-remove-icon size-3 stroke-[2.5]" />
      </TooltipIconButton>
    </AttachmentPrimitive.Remove>
  );
};

export const UserMessageAttachments: FC = () => {
  return (
    <div className="aui-user-message-attachments-end col-span-full col-start-1 row-start-1 flex w-full flex-row justify-end gap-2">
      <MessagePrimitive.Attachments>
        {() => <AttachmentUI />}
      </MessagePrimitive.Attachments>
    </div>
  );
};

export const ComposerAttachments: FC = () => {
  return (
    <div className="aui-composer-attachments flex w-full flex-row items-center gap-2 overflow-x-auto empty:hidden">
      <ComposerPrimitive.Attachments>
        {() => <AttachmentUI />}
      </ComposerPrimitive.Attachments>
    </div>
  );
};

export const ComposerAddAttachment: FC = () => {
  // LOCAL: upstream's "Add Attachment" -- the tooltip and the `aria-label`, the same
  // words twice -- is gone from this file and read from the `elements-files` catalog
  // instead. Only one of the two is drawn; both are copy.
  const { t } = useTranslation("elements-files");
  return (
    <ComposerPrimitive.AddAttachment asChild>
      <TooltipIconButton
        tooltip={t("attachment.add")}
        side="bottom"
        variant="ghost"
        size="icon"
        className="aui-composer-add-attachment text-muted-foreground hover:text-foreground hover:bg-muted-foreground/15 dark:border-muted-foreground/15 dark:hover:bg-muted-foreground/30 size-7 rounded-full active:scale-[0.96] motion-reduce:transition-none"
        aria-label={t("attachment.add")}
      >
        <PlusIcon className="aui-attachment-add-icon size-4" />
      </TooltipIconButton>
    </ComposerPrimitive.AddAttachment>
  );
};
