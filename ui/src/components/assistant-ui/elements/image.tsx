"use client";

// LOCAL: this copied file is TRANSLATED IN PLACE. Upstream's own words -- the alt
// fallbacks ("Image content" / "Image preview"), the loading sentence, the zoom
// overlay's three labels, the content-filter failure ("Image could not be generated"
// / "The provider blocked this image."), the clipboard refusal, and the action
// buttons' Download / Copy / Regenerate labels -- are gone from this file and read
// from the `elements-files` catalog instead (spec decision 5, which reverses
// flat-step-rows decision 9's "leave the copies untouched"). WHAT DOES NOT MOVE is
// the boundary: the image's bytes, its data URL, its MIME type and its filename are
// DATA and pass through untouched. The cost, written down: this file is no longer
// byte-comparable with upstream, so each deliberate edit below is marked `LOCAL:` --
// a marker says "this was changed on purpose", not "this is what upstream changed".
// Every non-word byte -- `data-slot`, class names, upstream identifiers -- is
// untouched.

import type { TFunction } from "i18next";
import {
  memo,
  useState,
  useEffect,
  useCallback,
  useRef,
  type PropsWithChildren,
} from "react";
import { createPortal } from "react-dom";
import { cva, type VariantProps } from "class-variance-authority";
import {
  CopyIcon,
  DownloadIcon,
  ImageIcon,
  ImageOffIcon,
  Loader2Icon,
  RefreshCwIcon,
  ShieldAlertIcon,
  XIcon,
} from "lucide-react";
import type {
  ImageMessagePart,
  ImageMessagePartComponent,
} from "@assistant-ui/react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

// LOCAL: the translator this file's words are read from, PINNED TO THIS FILE'S FACE.
// A bare `TFunction` would mean the default namespace; naming `elements-files` keeps
// only this catalog's keys compiling here, the same guard `format.ts` puts on its own
// module. Only `copyImagePart` takes one as an argument -- the components use
// `useTranslation` directly.
type Translate = TFunction<"elements-files">;

const extensionForMimeType = (mimeType?: string): string => {
  switch (mimeType) {
    case "image/png":
      return "png";
    case "image/jpeg":
    case "image/jpg":
      return "jpg";
    case "image/webp":
      return "webp";
    case "image/gif":
      return "gif";
    case "image/svg+xml":
      return "svg";
    default:
      return "png";
  }
};

const dataUriToBlob = (dataUri: string): Blob | null => {
  const commaIndex = dataUri.indexOf(",");
  const meta = commaIndex >= 0 ? dataUri.slice(0, commaIndex) : dataUri;
  const data = commaIndex >= 0 ? dataUri.slice(commaIndex + 1) : "";
  const mime =
    meta.match(/data:([^;]+)/i)?.[1]?.toLowerCase() ??
    "application/octet-stream";
  if (!/;base64/i.test(meta)) {
    const text = data.replace(/(?:%[0-9A-Fa-f]{2})+/g, (seq) => {
      try {
        return decodeURIComponent(seq);
      } catch {
        return seq;
      }
    });
    return new Blob([text], { type: mime });
  }
  let bytes: string;
  try {
    const base64 = data.replace(/%([\da-f]{2})/gi, (_match, hex: string) =>
      String.fromCharCode(Number.parseInt(hex, 16)),
    );
    bytes = atob(base64);
  } catch {
    return null;
  }
  const arr = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
  return new Blob([arr], { type: mime });
};

const mimeFromImage = (image: string): string | undefined =>
  image.match(/^data:([^;,]+)/i)?.[1]?.toLowerCase();

const downloadImagePart = (
  part: Pick<ImageMessagePart, "image" | "filename">,
): void => {
  if (typeof document === "undefined") return;
  const ext = extensionForMimeType(mimeFromImage(part.image));
  const filename = part.filename ?? `image.${ext}`;
  const isDataUri = /^data:/i.test(part.image);
  const blob = isDataUri ? dataUriToBlob(part.image) : null;
  if (isDataUri && !blob) return;
  const objectUrl = blob ? URL.createObjectURL(blob) : null;
  const href = objectUrl ?? part.image;
  const a = document.createElement("a");
  a.href = href;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  if (objectUrl) setTimeout(() => URL.revokeObjectURL(objectUrl), 40_000);
};

// LOCAL: the clipboard refusal is the interface's own sentence, so it takes the
// translator rather than being written here (spec decision 3 -- only the interface's
// words have a language).
const copyImagePart = async (
  part: Pick<ImageMessagePart, "image">,
  t: Translate,
): Promise<void> => {
  if (
    typeof navigator === "undefined" ||
    !navigator.clipboard ||
    typeof ClipboardItem === "undefined"
  ) {
    throw new Error(t("image.clipboardUnavailable"));
  }
  const blob = /^data:/i.test(part.image)
    ? dataUriToBlob(part.image)
    : await fetch(part.image).then((r) => r.blob());
  if (!blob) return;
  const mime = mimeFromImage(part.image) ?? blob.type ?? "image/png";
  await navigator.clipboard.write([new ClipboardItem({ [mime]: blob })]);
};

const imageVariants = cva(
  "aui-image-root relative overflow-hidden rounded-lg",
  {
    variants: {
      variant: {
        outline: "border-border border",
        ghost: "",
        muted: "bg-muted/50",
      },
      size: {
        sm: "max-w-64",
        default: "max-w-96",
        lg: "max-w-[512px]",
        full: "w-full",
      },
    },
    defaultVariants: {
      variant: "outline",
      size: "default",
    },
  },
);

export type ImageRootProps = React.ComponentProps<"div"> &
  VariantProps<typeof imageVariants>;

function ImageRoot({
  className,
  variant,
  size,
  children,
  ...props
}: ImageRootProps) {
  return (
    <div
      data-slot="image-root"
      data-variant={variant}
      data-size={size}
      className={cn(imageVariants({ variant, size, className }))}
      {...props}
    >
      {children}
    </div>
  );
}

type ImagePreviewProps = Omit<React.ComponentProps<"img">, "children"> & {
  containerClassName?: string;
};

function ImagePreview({
  className,
  containerClassName,
  onLoad,
  onError,
  alt,
  src,
  ...props
}: ImagePreviewProps) {
  // LOCAL: upstream's "Image content" alt fallback is gone from this file and read
  // from the `elements-files` catalog instead. A caller that passes its own alt -- the
  // filename, which is data -- still wins.
  const { t } = useTranslation("elements-files");
  const imgRef = useRef<HTMLImageElement>(null);
  const [loadedSrc, setLoadedSrc] = useState<string | undefined>(undefined);
  const [errorSrc, setErrorSrc] = useState<string | undefined>(undefined);

  const loaded = loadedSrc === src;
  const error = errorSrc === src;

  useEffect(() => {
    const image = imgRef.current;
    if (typeof src !== "string" || !image?.complete) return;
    if (image.naturalWidth > 0) setLoadedSrc(src);
    else setErrorSrc(src);
  }, [src]);

  return (
    <div
      data-slot="image-preview"
      className={cn("relative min-h-32", containerClassName)}
    >
      {!loaded && !error && (
        <div
          data-slot="image-preview-loading"
          className="bg-muted/50 absolute inset-0 flex items-center justify-center"
        >
          <ImageIcon className="text-muted-foreground size-8 animate-pulse" />
        </div>
      )}
      {error ? (
        <div
          data-slot="image-preview-error"
          className="bg-muted/50 flex min-h-32 items-center justify-center p-4"
        >
          <ImageOffIcon className="text-muted-foreground size-8" />
        </div>
      ) : (
        <img
          ref={imgRef}
          src={src}
          alt={alt ?? t("image.alt")}
          className={cn(
            "block h-auto w-full object-contain",
            !loaded && "invisible",
            className,
          )}
          onLoad={(e) => {
            if (typeof src === "string") setLoadedSrc(src);
            onLoad?.(e);
          }}
          onError={(e) => {
            if (typeof src === "string") setErrorSrc(src);
            onError?.(e);
          }}
          {...props}
        />
      )}
    </div>
  );
}

function ImageFilename({
  className,
  children,
  ...props
}: React.ComponentProps<"span">) {
  if (!children) return null;

  return (
    <span
      data-slot="image-filename"
      className={cn(
        "text-muted-foreground block truncate px-2 py-1.5 text-xs",
        className,
      )}
      {...props}
    >
      {children}
    </span>
  );
}

type ImageZoomProps = PropsWithChildren<{
  src: string;
  alt?: string;
}>;

function ImageZoom({ src, alt, children }: ImageZoomProps) {
  // LOCAL: upstream's zoom labels -- "Click to zoom image", "Zoomed image" and
  // "Close zoomed image" -- and its "Image preview" alt fallback are gone from this
  // file and read from the `elements-files` catalog instead. A caller that passes its
  // own alt -- the filename, which is data -- still wins.
  const { t } = useTranslation("elements-files");
  const [isOpen, setIsOpen] = useState(false);
  const triggerRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);

  const handleOpen = useCallback(() => setIsOpen(true), []);
  const handleClose = useCallback(() => {
    setIsOpen(false);
    triggerRef.current?.focus();
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        handleClose();
        return;
      }
      if (e.key !== "Tab") return;
      const focusables = overlayRef.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
      );
      const first = focusables?.[0];
      const last = focusables?.[focusables.length - 1];
      if (!first || !last) return;
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, handleClose]);

  useEffect(() => {
    if (!isOpen) return;
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = originalOverflow;
    };
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) closeRef.current?.focus();
  }, [isOpen]);

  return (
    <>
      <div
        ref={triggerRef}
        onClick={handleOpen}
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
        role="button"
        tabIndex={0}
        className="aui-image-zoom-trigger cursor-zoom-in"
        aria-label={t("image.zoom")}
      >
        {children}
      </div>
      {isOpen &&
        createPortal(
          <div
            ref={overlayRef}
            data-slot="image-zoom-overlay"
            role="dialog"
            aria-modal="true"
            className="aui-image-zoom-overlay fade-in animate-in fixed inset-0 z-50 flex items-center justify-center bg-black/80 duration-200"
            onClick={handleClose}
            aria-label={t("image.zoomOverlay")}
          >
            <img
              data-slot="image-zoom-content"
              src={src}
              alt={alt ?? t("image.previewAlt")}
              className="aui-image-zoom-content fade-in zoom-in-95 animate-in max-h-[90vh] max-w-[90vw] cursor-zoom-out object-contain duration-200"
              onClick={(e) => {
                e.stopPropagation();
                handleClose();
              }}
            />
            <button
              ref={closeRef}
              type="button"
              aria-label={t("image.zoomClose")}
              onClick={(e) => {
                e.stopPropagation();
                handleClose();
              }}
              className="text-muted-foreground hover:text-foreground bg-background/80 absolute end-4 top-4 cursor-pointer rounded-md p-2"
            >
              <XIcon className="size-5" />
            </button>
          </div>,
          document.body,
        )}
    </>
  );
}

function ImageGenerating({ className }: { className?: string }) {
  // LOCAL: upstream's `sr-only` "Generating image…" is gone from this file and read
  // from the `elements-files` catalog instead. It is never drawn, but it is what a
  // screen reader announces while a generation is in flight, so it is copy.
  const { t } = useTranslation("elements-files");
  return (
    <div
      data-slot="image-generating"
      className={cn(
        "bg-muted/50 flex min-h-32 items-center justify-center p-4",
        className,
      )}
    >
      <Loader2Icon className="text-muted-foreground size-8 animate-spin" />
      <span className="sr-only">{t("image.generating")}</span>
    </div>
  );
}

function ImageContentFilterError({
  className,
  reason,
}: {
  className?: string;
  reason?: string;
}) {
  // LOCAL: upstream's "Image could not be generated" is gone from this file and read
  // from the `elements-files` catalog instead. The `reason` stays whatever the caller
  // passed -- the one sentence this file hands it is the catalog's (see `ImageImpl`).
  const { t } = useTranslation("elements-files");
  return (
    <div
      data-slot="image-content-filter-error"
      className={cn(
        "bg-muted/50 flex min-h-32 flex-col items-center justify-center gap-2 p-4 text-center",
        className,
      )}
    >
      <ShieldAlertIcon className="text-muted-foreground size-8" />
      <p className="text-sm font-medium">{t("image.generateFailed")}</p>
      {reason && <p className="text-muted-foreground text-xs">{reason}</p>}
    </div>
  );
}

export type ImageActionsProps = {
  part: ImageMessagePart;
  /**
   * Wire to your own generation call to show a regenerate button. The button
   * renders only when this is set and the part carries a `prompt`.
   */
  onRegenerate?: () => void | Promise<void>;
  className?: string;
};

function RegenerateButton({
  onRegenerate,
}: {
  onRegenerate: () => void | Promise<void>;
}) {
  // LOCAL: upstream's "Regenerate image" aria-label is gone from this file and read
  // from the `elements-files` catalog instead.
  const { t } = useTranslation("elements-files");
  const [isRegenerating, setIsRegenerating] = useState(false);
  return (
    <button
      type="button"
      onClick={async () => {
        setIsRegenerating(true);
        try {
          await onRegenerate();
        } catch {
        } finally {
          setIsRegenerating(false);
        }
      }}
      disabled={isRegenerating}
      data-slot="image-regenerate"
      aria-label={t("image.regenerate")}
      className="hover:bg-muted inline-flex size-7 items-center justify-center rounded disabled:opacity-50"
    >
      <RefreshCwIcon
        className={cn("size-4", isRegenerating && "animate-spin")}
      />
    </button>
  );
}

function ImageActions({ part, onRegenerate, className }: ImageActionsProps) {
  // LOCAL: upstream's "Download image" and "Copy image" aria-labels are gone from
  // this file and read from the `elements-files` catalog instead. The copy path hands
  // the translator to `copyImagePart`, whose refusal is also a catalog sentence.
  const { t } = useTranslation("elements-files");
  return (
    <div
      data-slot="image-actions"
      className={cn("flex items-center gap-1 p-1", className)}
    >
      <button
        type="button"
        onClick={() => downloadImagePart(part)}
        data-slot="image-download"
        aria-label={t("image.download")}
        className="hover:bg-muted inline-flex size-7 items-center justify-center rounded"
      >
        <DownloadIcon className="size-4" />
      </button>
      <button
        type="button"
        onClick={() => {
          copyImagePart(part, t).catch(() => {});
        }}
        data-slot="image-copy"
        aria-label={t("image.copy")}
        className="hover:bg-muted inline-flex size-7 items-center justify-center rounded"
      >
        <CopyIcon className="size-4" />
      </button>
      {onRegenerate && <RegenerateButton onRegenerate={onRegenerate} />}
    </div>
  );
}

const ImageImpl: ImageMessagePartComponent = (props) => {
  // LOCAL: upstream's content-filter reason, "The provider blocked this image.", and
  // its "Image content" alt fallback are gone from this file and read from the
  // `elements-files` catalog instead. A `filename` the part carries is data and still
  // wins over the fallback.
  const { t } = useTranslation("elements-files");
  const { image, filename, status } = props;

  if (status?.type === "running") {
    return (
      <ImageRoot>
        <ImageGenerating />
        <ImageFilename>{filename}</ImageFilename>
      </ImageRoot>
    );
  }

  if (status?.type === "incomplete" && status.reason === "content-filter") {
    return (
      <ImageRoot>
        <ImageContentFilterError reason={t("image.blocked")} />
      </ImageRoot>
    );
  }

  return (
    <ImageRoot>
      <ImageZoom src={image} alt={filename || t("image.alt")}>
        <ImagePreview src={image} alt={filename || t("image.alt")} />
      </ImageZoom>
      <ImageFilename>{filename}</ImageFilename>
    </ImageRoot>
  );
};

const Image = memo(ImageImpl) as unknown as ImageMessagePartComponent & {
  Root: typeof ImageRoot;
  Preview: typeof ImagePreview;
  Filename: typeof ImageFilename;
  Zoom: typeof ImageZoom;
  Actions: typeof ImageActions;
  Generating: typeof ImageGenerating;
  ContentFilterError: typeof ImageContentFilterError;
};

Image.displayName = "Image";
Image.Root = ImageRoot;
Image.Preview = ImagePreview;
Image.Filename = ImageFilename;
Image.Zoom = ImageZoom;
Image.Actions = ImageActions;
Image.Generating = ImageGenerating;
Image.ContentFilterError = ImageContentFilterError;

export {
  Image,
  ImageRoot,
  ImagePreview,
  ImageFilename,
  ImageZoom,
  ImageActions,
  ImageGenerating,
  ImageContentFilterError,
  imageVariants,
};
