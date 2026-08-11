import jsPDF from 'jspdf';

let fontLoaded = false;
let loadingPromise: Promise<void> | null = null;

/**
 * Загружает и регистрирует шрифт Roboto с поддержкой кириллицы в jsPDF.
 * Вызывается один раз, последующие вызовы используют кеш.
 */
export async function ensurePdfFont(doc: jsPDF): Promise<void> {
  if (fontLoaded) return;
  if (loadingPromise) return loadingPromise;

  loadingPromise = (async () => {
    try {
      // Загружаем Regular
      const regularRes = await fetch('/fonts/Roboto-Regular.ttf');
      if (!regularRes.ok) throw new Error('Failed to load Roboto-Regular.ttf');
      const regularBuffer = await regularRes.arrayBuffer();
      const regularBase64 = arrayBufferToBase64(regularBuffer);

      // Загружаем Bold
      const boldRes = await fetch('/fonts/Roboto-Bold.ttf');
      if (!boldRes.ok) throw new Error('Failed to load Roboto-Bold.ttf');
      const boldBuffer = await boldRes.arrayBuffer();
      const boldBase64 = arrayBufferToBase64(boldBuffer);

      // Регистрируем в Virtual File System jsPDF
      doc.addFileToVFS('Roboto-Regular.ttf', regularBase64);
      doc.addFont('Roboto-Regular.ttf', 'Roboto', 'normal');

      doc.addFileToVFS('Roboto-Bold.ttf', boldBase64);
      doc.addFont('Roboto-Bold.ttf', 'Roboto', 'bold');

      // Устанавливаем как шрифт по умолчанию
      doc.setFont('Roboto', 'normal');

      fontLoaded = true;
      console.log('✅ PDF font loaded: Roboto (with Cyrillic support)');
    } catch (err) {
      console.error('❌ Failed to load PDF font:', err);
      throw err;
    }
  })();

  return loadingPromise;
}

/**
 * Конвертация ArrayBuffer в Base64 (без btoa для больших файлов)
 */
function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunkSize = 8192;
  for (let i = 0; i < bytes.byteLength; i += chunkSize) {
    const chunk = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode.apply(null, Array.from(chunk));
  }
  return btoa(binary);
}