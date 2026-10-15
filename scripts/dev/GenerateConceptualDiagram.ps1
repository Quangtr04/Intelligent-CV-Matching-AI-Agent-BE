[CmdletBinding()]
param(
    [string]$ModelPath,
    [string]$OutputDirectory
)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Drawing
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$ModelPath) { $ModelPath=Join-Path $repositoryRoot 'docs/diagrams/edunexus-conceptual-model.json' }
if (!$OutputDirectory) { $OutputDirectory=Join-Path $repositoryRoot 'docs/diagrams' }
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if (!($OutputDirectory+'\').StartsWith($repositoryRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must remain inside this repository.'
}
$model=Get-Content -LiteralPath $ModelPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($model.fileStem -ne 'edunexus-conceptual-diagram') { throw 'Unexpected output file name.' }
if (!(Test-Path -LiteralPath $OutputDirectory)) { New-Item -ItemType Directory -Path $OutputDirectory | Out-Null }
$utf8=New-Object Text.UTF8Encoding($false)
$script:measurementBitmap=New-Object Drawing.Bitmap(1,1)
$script:measurementGraphics=[Drawing.Graphics]::FromImage($script:measurementBitmap)
$script:measurementGraphics.TextRenderingHint=[Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$script:fontCache=@{}

# Native SVG/PNG drawing helpers copied from the repository's use-case renderer.
# This script does not execute or regenerate the existing use-case diagrams.


function Get-Field($Object, [string]$Name, $Default = $null) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Escape-Xml([string]$Value) {
    return [System.Security.SecurityElement]::Escape($Value)
}

function Number([double]$Value) {
    return $Value.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-Font([double]$Size, [bool]$Bold = $false) {
    $key = "$Size/$Bold"
    if (-not $script:fontCache.ContainsKey($key)) {
        $style = [System.Drawing.FontStyle]::Regular
        if ($Bold) { $style = [System.Drawing.FontStyle]::Bold }
        $script:fontCache[$key] = New-Object System.Drawing.Font('Segoe UI', [single]$Size, $style, [System.Drawing.GraphicsUnit]::Pixel)
    }
    return $script:fontCache[$key]
}

function Get-TextWidth([string]$Text, [double]$Size, [bool]$Bold = $false) {
    return $script:measurementGraphics.MeasureString($Text, (Get-Font $Size $Bold), 10000, [System.Drawing.StringFormat]::GenericTypographic).Width
}

function Wrap-Text([string]$Text, [double]$Width, [double]$Size, [bool]$Bold = $false) {
    $result = New-Object 'System.Collections.Generic.List[string]'
    $Text = $Text.Replace('\n', "`n")
    foreach ($paragraph in ($Text -split "`r?`n")) {
        if ($paragraph.Length -eq 0) { $result.Add(''); continue }
        $line = ''
        foreach ($word in ($paragraph -split '\s+')) {
            $candidate = ($line + ' ' + $word).Trim()
            if ($line -and (Get-TextWidth $candidate $Size $Bold) -gt $Width) {
                $result.Add($line)
                $line = $word
            } else { $line = $candidate }
        }
        $result.Add($line)
    }
    return ,$result.ToArray()
}

function Add-Text($Scene, [string]$Text, [double]$X, [double]$Y, [double]$Width, [double]$Height,
    [double]$Size = 18, [string]$Color = '#263449', [bool]$Bold = $false,
    [string]$Align = 'center', [string]$Vertical = 'middle') {
    $lines = Wrap-Text $Text $Width $Size $Bold
    $lineHeight = $Size * 1.3
    $top = $Y
    if ($Vertical -eq 'middle') { $top += ($Height - $lines.Count * $lineHeight) / 2 }
    foreach ($line in $lines) {
        $Scene.Add(@{type='text';text=$line;x=$X;y=$top;width=$Width;height=$lineHeight;size=$Size;color=$Color;bold=$Bold;align=$Align})
        $top += $lineHeight
    }
}

function Add-Shape($Scene, [string]$Type, [double]$X, [double]$Y, [double]$Width, [double]$Height,
    [string]$Fill = '#ffffff', [string]$Stroke = '#52647b', [double]$StrokeWidth = 1.5,
    [bool]$Dashed = $false, [double]$Radius = 0) {
    $Scene.Add(@{type=$Type;x=$X;y=$Y;width=$Width;height=$Height;fill=$Fill;stroke=$Stroke;strokeWidth=$StrokeWidth;dashed=$Dashed;radius=$Radius})
}

function Get-Arrow($Item) {
    $end = $Item.points[$Item.points.Count - 1]
    $before = $Item.points[$Item.points.Count - 2]
    $dx = $end.x - $before.x
    $dy = $end.y - $before.y
    $distance = [Math]::Sqrt($dx * $dx + $dy * $dy)
    if ($distance -lt 0.01) { throw 'Arrow cannot have an empty final segment.' }
    $ux = $dx / $distance
    $uy = $dy / $distance
    $length = 12
    $halfWidth = 5.5
    if ($Item.edgeType -eq 'generalization') { $length = 16; $halfWidth = 8 }
    return @(
        @{x=($end.x - $length*$ux + $halfWidth*$uy);y=($end.y - $length*$uy - $halfWidth*$ux)},
        @{x=$end.x;y=$end.y},
        @{x=($end.x - $length*$ux - $halfWidth*$uy);y=($end.y - $length*$uy + $halfWidth*$ux)}
    )
}

function Write-Svg($Page, $Scene, [string]$Path) {
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
    [void]$builder.AppendLine('<svg xmlns="http://www.w3.org/2000/svg" width="' + $Page.width + '" height="' + $Page.height + '" viewBox="0 0 ' + $Page.width + ' ' + $Page.height + '" role="img" aria-labelledby="title description">')
    [void]$builder.AppendLine('<title id="title">' + (Escape-Xml $Page.title) + '</title>')
    [void]$builder.AppendLine('<desc id="description">' + (Escape-Xml (Get-Field $Page 'subtitle' $Page.title)) + '</desc>')
    foreach ($item in $Scene) {
        switch ($item.type) {
            'text' {
                $tx = $item.x
                $anchor = 'start'
                if ($item.align -eq 'center') { $tx += $item.width / 2; $anchor = 'middle' }
                if ($item.align -eq 'right') { $tx += $item.width; $anchor = 'end' }
                $weight = '400'
                if ($item.bold) { $weight = '600' }
                $line = '<text x="' + (Number $tx) + '" y="' + (Number ($item.y + $item.size)) + '" text-anchor="' + $anchor + '" font-family="Segoe UI,Arial,sans-serif" font-size="' + (Number $item.size) + '" font-weight="' + $weight + '" fill="' + $item.color + '">' + (Escape-Xml $item.text) + '</text>'
            }
            'line' {
                $points = ($item.points | ForEach-Object { (Number $_.x) + ',' + (Number $_.y) }) -join ' '
                $dash = ''
                if ($item.dashed) { $dash = ' stroke-dasharray="7 5"' }
                $line = '<polyline points="' + $points + '" fill="none" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '" stroke-linejoin="round" stroke-linecap="round"' + $dash + '/>'
                if ($item.edgeType -in @('include','extend','generalization')) {
                    $arrow = ((Get-Arrow $item) | ForEach-Object { (Number $_.x) + ',' + (Number $_.y) }) -join ' '
                    $tag = 'polyline'
                    $fill = 'none'
                    if ($item.edgeType -eq 'generalization') { $tag = 'polygon'; $fill = '#ffffff' }
                    $line += '<' + $tag + ' points="' + $arrow + '" fill="' + $fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
                }
            }
            'ellipse' {
                $line = '<ellipse cx="' + (Number ($item.x + $item.width/2)) + '" cy="' + (Number ($item.y + $item.height/2)) + '" rx="' + (Number ($item.width/2)) + '" ry="' + (Number ($item.height/2)) + '" fill="' + $item.fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
            }
            'rect' {
                $line = '<rect x="' + (Number $item.x) + '" y="' + (Number $item.y) + '" width="' + (Number $item.width) + '" height="' + (Number $item.height) + '" rx="' + (Number $item.radius) + '" fill="' + $item.fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
            }
        }
        [void]$builder.AppendLine($line)
    }
    [void]$builder.AppendLine('</svg>')
    [System.IO.File]::WriteAllText($Path, $builder.ToString(), $utf8)
}

function Get-Color([string]$Value) {
    return [System.Drawing.ColorTranslator]::FromHtml($Value)
}

function Write-Png($Page, $Scene, [string]$Path) {
    $bitmap = New-Object System.Drawing.Bitmap([int]($Page.width*2), [int]($Page.height*2), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear((Get-Color (Get-PageTheme $Page).background))
        $graphics.ScaleTransform(2, 2)
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        foreach ($item in $Scene) {
            if ($item.type -eq 'text') {
                $brush = New-Object System.Drawing.SolidBrush((Get-Color $item.color))
                $format = [System.Drawing.StringFormat]::GenericTypographic.Clone()
                try {
                    $format.FormatFlags = $format.FormatFlags -bor [System.Drawing.StringFormatFlags]::NoWrap
                    if ($item.align -eq 'center') { $format.Alignment = [System.Drawing.StringAlignment]::Center }
                    if ($item.align -eq 'right') { $format.Alignment = [System.Drawing.StringAlignment]::Far }
                    $textHeight=[Math]::Max($item.height,$item.size*1.6)
                    $rectangle = New-Object System.Drawing.RectangleF([single]$item.x,[single]$item.y,[single]$item.width,[single]$textHeight)
                    $graphics.DrawString($item.text, (Get-Font $item.size $item.bold), $brush, $rectangle, $format)
                } finally { $brush.Dispose(); $format.Dispose() }
                continue
            }
            $stroke = $item.stroke
            if ($stroke -eq 'none') { $stroke = '#ffffff' }
            $pen = New-Object System.Drawing.Pen((Get-Color $stroke), [single][Math]::Max(0.1,$item.strokeWidth))
            $brush = $null
            try {
                $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
                if ($item.dashed) { $pen.DashPattern = [single[]]@(4.375,3.125) }
                if ($item.type -eq 'line') {
                    $points = [System.Drawing.PointF[]]@($item.points | ForEach-Object { New-Object System.Drawing.PointF([single]$_.x,[single]$_.y) })
                    $graphics.DrawLines($pen,$points)
                    if ($item.edgeType -in @('include','extend','generalization')) {
                        $pen.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Solid
                        $arrow = [System.Drawing.PointF[]]@((Get-Arrow $item) | ForEach-Object { New-Object System.Drawing.PointF([single]$_.x,[single]$_.y) })
                        if ($item.edgeType -eq 'generalization') {
                            $graphics.FillPolygon([System.Drawing.Brushes]::White,$arrow)
                            $graphics.DrawPolygon($pen,$arrow)
                        } else { $graphics.DrawLines($pen,$arrow) }
                    }
                    continue
                }
                $brush = New-Object System.Drawing.SolidBrush((Get-Color $item.fill))
                $x=[single]$item.x; $y=[single]$item.y; $w=[single]$item.width; $h=[single]$item.height
                if ($item.type -eq 'ellipse') {
                    $graphics.FillEllipse($brush,$x,$y,$w,$h)
                    if ($item.stroke -ne 'none') { $graphics.DrawEllipse($pen,$x,$y,$w,$h) }
                } elseif ($item.radius -gt 0) {
                    $roundedPath = New-Object System.Drawing.Drawing2D.GraphicsPath
                    try {
                        $r=[single]($item.radius*2)
                        $roundedPath.AddArc($x,$y,$r,$r,180,90)
                        $roundedPath.AddArc(($x+$w-$r),$y,$r,$r,270,90)
                        $roundedPath.AddArc(($x+$w-$r),($y+$h-$r),$r,$r,0,90)
                        $roundedPath.AddArc($x,($y+$h-$r),$r,$r,90,90)
                        $roundedPath.CloseFigure()
                        $graphics.FillPath($brush,$roundedPath)
                        if ($item.stroke -ne 'none') { $graphics.DrawPath($pen,$roundedPath) }
                    } finally { $roundedPath.Dispose() }
                } else {
                    $graphics.FillRectangle($brush,$x,$y,$w,$h)
                    if ($item.stroke -ne 'none') { $graphics.DrawRectangle($pen,$x,$y,$w,$h) }
                }
            } finally { $pen.Dispose(); if ($null -ne $brush) { $brush.Dispose() } }
        }
        $bitmap.Save($Path,[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

function Add-XmlElement($Document, $Parent, [string]$Name, [hashtable]$Attributes) {
    $element = $Document.CreateElement($Name)
    foreach ($key in $Attributes.Keys) { $element.SetAttribute($key, [string]$Attributes[$key]) }
    [void]$Parent.AppendChild($element)
    return $element
}

function Add-DrawioVertex($Document, $Root, [string]$Id, [string]$Label, [string]$Style,
    [double]$X, [double]$Y, [double]$Width, [double]$Height, [string]$ParentId = '1') {
    $cell = Add-XmlElement $Document $Root 'mxCell' @{id=$Id;value=$Label.Replace('\n',"`n");style=$Style;vertex='1';parent=$ParentId}
    [void](Add-XmlElement $Document $cell 'mxGeometry' @{x=(Number $X);y=(Number $Y);width=(Number $Width);height=(Number $Height);as='geometry'})
    return $cell
}

function Get-PageScale($Page) {
    return [double](Get-Field $Page 'contentScale' 1)
}

function Get-PageTheme($Page) {
    $defaults=[ordered]@{
        background='#FFFFFF'; titleColor='#0F172A'; subtitleColor='#64748B';
        nodeFill='#FFFFFF'; nodeText='#0F172A'; bodyText='#475569';
        labelFill='#FFFFFF'; labelText='#475569';
        noteFill='#F8FAFC'; noteStroke='#E2E8F0'; noteText='#475569'
    }
    $configured=Get-Field $Page 'theme' $null
    $theme=[ordered]@{}
    foreach ($key in $defaults.Keys) {
        $value=$defaults[$key]
        if ($null -ne $configured) { $value=Get-Field $configured $key $value }
        $theme[$key]=$value
    }
    return [pscustomobject]$theme
}

function Get-NodeTextLayout($Node, [double]$Scale) {
    $titleSize=[double](Get-Field $Node 'fontSize' (22*$Scale))
    $bodySize=[double](Get-Field $Node 'bodySize' (16*$Scale))
    $padding=12*$Scale
    $textWidth=$Node.width-2*$padding
    $titleLines=Wrap-Text $Node.title $textWidth $titleSize $true
    $titleHeight=[Math]::Max(30*$Scale,$titleLines.Count*$titleSize*1.3)
    $titleY=$padding
    if (@(Get-Field $Node 'body' @()).Count -eq 0) {
        $titleY=($Node.height-$titleHeight)/2
    }
    return @{
        padding=$padding; width=$textWidth; titleSize=$titleSize; bodySize=$bodySize;
        titleY=$titleY; titleHeight=$titleHeight; bodyY=($padding+$titleHeight+5*$Scale)
    }
}

function Add-MarkerLine($Scene, $Start, $End, [string]$Color, [double]$Width) {
    $Scene.Add(@{
        type='line';points=@($Start,$End);stroke=$Color;strokeWidth=$Width;
        dashed=$false;edgeType='marker'
    })
}

function Get-MarkerPoint($Endpoint, [double]$UX, [double]$UY, [double]$Distance,
    [double]$Across = 0) {
    return @{
        x=($Endpoint.x+$Distance*$UX-$Across*$UY);
        y=($Endpoint.y+$Distance*$UY+$Across*$UX)
    }
}

function Add-CardinalityMarker($Scene, $Endpoint, $Adjacent, [string]$Cardinality,
    [string]$Color, [double]$Scale, [string]$Background = '#FFFFFF') {
    if (!$Cardinality) { return }
    $dx=$Adjacent.x-$Endpoint.x
    $dy=$Adjacent.y-$Endpoint.y
    $length=[Math]::Sqrt($dx*$dx+$dy*$dy)
    if ($length -lt 0.01) { throw 'Cardinality marker needs a nonempty endpoint segment.' }
    $ux=$dx/$length
    $uy=$dy/$length
    $width=1.8*$Scale
    if ($Cardinality -in @('0..n','1..n')) {
        $join=Get-MarkerPoint $Endpoint $ux $uy (14*$Scale)
        foreach ($across in @(-7,0,7)) {
            $tip=Get-MarkerPoint $Endpoint $ux $uy (2*$Scale) ($across*$Scale)
            Add-MarkerLine $Scene $tip $join $Color $width
        }
    } else {
        $a=Get-MarkerPoint $Endpoint $ux $uy (7*$Scale) (-7*$Scale)
        $b=Get-MarkerPoint $Endpoint $ux $uy (7*$Scale) (7*$Scale)
        Add-MarkerLine $Scene $a $b $Color $width
    }
    if ($Cardinality -in @('0..1','0..n')) {
        $center=Get-MarkerPoint $Endpoint $ux $uy (22*$Scale)
        Add-Shape $Scene 'ellipse' ($center.x-5*$Scale) ($center.y-5*$Scale) (10*$Scale) (10*$Scale) $Background $Color $width
    } else {
        $distance=17*$Scale
        if ($Cardinality -eq '1..n') { $distance=23*$Scale }
        $a=Get-MarkerPoint $Endpoint $ux $uy $distance (-7*$Scale)
        $b=Get-MarkerPoint $Endpoint $ux $uy $distance (7*$Scale)
        Add-MarkerLine $Scene $a $b $Color $width
    }
}

function Get-DrawioCardinality([string]$Cardinality) {
    switch ($Cardinality) {
        '1' { return 'ERmandOne' }
        '0..1' { return 'ERzeroToOne' }
        '0..n' { return 'ERzeroToMany' }
        '1..n' { return 'ERoneToMany' }
        default { return 'none' }
    }
}

function Assert-Model($Model) {
    if (!$Model.pages -or $Model.pages.Count -eq 0) { throw 'At least one diagram page is required.' }
    $pageIds=New-Object 'System.Collections.Generic.HashSet[string]'
    $suffixes=New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($page in $Model.pages) {
        if (!$pageIds.Add($page.id)) { throw ('Duplicate page: '+$page.id) }
        $suffix=[string](Get-Field $page 'fileSuffix' '')
        if ($suffix -notmatch '^(|-[a-z0-9][a-z0-9-]*)$' -or !$suffixes.Add($suffix)) {
            throw ('Unsafe or duplicate file suffix on '+$page.id)
        }
        if ($page.width -le 0 -or $page.height -le 0) { throw ('Invalid canvas on '+$page.id) }
        $scale=Get-PageScale $page
        if ($scale -le 0 -or $scale -gt 2) { throw ('Invalid content scale on '+$page.id) }
        $nodeIds=New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($node in $page.nodes) {
            if (!$nodeIds.Add($node.id)) { throw ('Duplicate concept on '+$page.id+': '+$node.id) }
            if ($node.width -le 0 -or $node.height -le 0 -or $node.x -lt 0 -or $node.y -lt 0 -or
                $node.x+$node.width -gt $page.width -or $node.y+$node.height -gt $page.height) {
                throw ('Concept outside canvas on '+$page.id+': '+$node.id)
            }
        }
        foreach ($group in $page.groups) {
            if ($group.x -lt 0 -or $group.y -lt 0 -or $group.x+$group.width -gt $page.width -or
                $group.y+$group.height -gt $page.height) {
                throw ('Group outside canvas on '+$page.id+': '+$group.id)
            }
        }
        $edgeIds=New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($edge in $page.edges) {
            if (!$edgeIds.Add($edge.id) -or $nodeIds.Contains($edge.id)) {
                throw ('Duplicate relationship on '+$page.id+': '+$edge.id)
            }
            if (!$nodeIds.Contains($edge.source) -or !$nodeIds.Contains($edge.target)) {
                throw ('Unknown concept on '+$page.id+': '+$edge.id)
            }
            if ($edge.points.Count -lt 2) { throw ('Relationship needs two endpoints: '+$edge.id) }
            if ($edge.labelX -lt 0 -or $edge.labelY -lt 0 -or $edge.labelWidth -le 0 -or
                $edge.labelHeight -le 0 -or $edge.labelX+$edge.labelWidth -gt $page.width -or
                $edge.labelY+$edge.labelHeight -gt $page.height) {
                throw ('Relationship label outside canvas on '+$page.id+': '+$edge.id)
            }
            foreach ($point in $edge.points) {
                if ($point.x -lt 0 -or $point.y -lt 0 -or $point.x -gt $page.width -or $point.y -gt $page.height) {
                    throw ('Relationship outside canvas on '+$page.id+': '+$edge.id)
                }
            }
            foreach ($field in @('startCardinality','endCardinality')) {
                $cardinality=Get-Field $edge $field ''
                if ($cardinality -and $cardinality -notin @('1','0..1','0..n','1..n')) {
                    throw ('Unknown cardinality on '+$edge.id+': '+$cardinality)
                }
            }
            $source=$page.nodes | Where-Object { $_.id -eq $edge.source } | Select-Object -First 1
            $target=$page.nodes | Where-Object { $_.id -eq $edge.target } | Select-Object -First 1
            $endpoints=@(
                @{node=$source;point=$edge.points[0]},
                @{node=$target;point=$edge.points[$edge.points.Count-1]}
            )
            foreach ($endpoint in $endpoints) {
                $node=$endpoint.node
                $point=$endpoint.point
                $inside=$point.x -ge $node.x-0.01 -and $point.x -le $node.x+$node.width+0.01 -and
                    $point.y -ge $node.y-0.01 -and $point.y -le $node.y+$node.height+0.01
                $boundary=[Math]::Abs($point.x-$node.x) -lt 0.02 -or
                    [Math]::Abs($point.x-($node.x+$node.width)) -lt 0.02 -or
                    [Math]::Abs($point.y-$node.y) -lt 0.02 -or
                    [Math]::Abs($point.y-($node.y+$node.height)) -lt 0.02
                if (!$inside -or !$boundary) { throw ('Relationship endpoint misses concept border: '+$edge.id) }
            }
        }
        if ($page.notesX -lt 0 -or $page.notesY -lt 0 -or
            $page.notesX+$page.notesWidth -gt $page.width -or $page.notesY+$page.notesHeight -gt $page.height) {
            throw ('Notes outside canvas on '+$page.id)
        }
    }
}

function Write-ConceptualDrawio($Model, [string]$Path) {
    $document=New-Object System.Xml.XmlDocument
    $mxFile=Add-XmlElement $document $document 'mxfile' @{host='app.diagrams.net';version='24.7.17';type='device'}
    $textStyle='text;html=0;whiteSpace=wrap;strokeColor=none;fillColor=none;fontFamily=Segoe UI;'
    foreach ($page in $Model.pages) {
        $scale=Get-PageScale $page
        $theme=Get-PageTheme $page
        $diagram=Add-XmlElement $document $mxFile 'diagram' @{id=$page.id;name=$page.name}
        $graph=Add-XmlElement $document $diagram 'mxGraphModel' @{
            dx=$page.width;dy=$page.height;grid='1';gridSize='10';guides='1';tooltips='1';connect='1';arrows='1';
            fold='1';page='1';pageScale='1';pageWidth=$page.width;pageHeight=$page.height;math='0';shadow='0';background=$theme.background
        }
        $root=Add-XmlElement $document $graph 'root' @{}
        [void](Add-XmlElement $document $root 'mxCell' @{id='0'})
        [void](Add-XmlElement $document $root 'mxCell' @{id='1';parent='0'})
        [void](Add-DrawioVertex $document $root 'diagram-title' $page.title ($textStyle+"align=left;fontSize=36;fontStyle=1;fontColor=$($theme.titleColor);") 70 26 ($page.width-140) 64)
        [void](Add-DrawioVertex $document $root 'diagram-subtitle' $page.subtitle ($textStyle+"align=left;fontSize=20;fontColor=$($theme.subtitleColor);") 72 92 ($page.width-144) 36)
        foreach ($group in $page.groups) {
            [void](Add-DrawioVertex $document $root ('group-'+$group.id) '' "rounded=1;arcSize=5;whiteSpace=wrap;html=0;fillColor=$($group.fill);strokeColor=#DCE5EF;strokeWidth=$(Number (1.5*$scale));" $group.x $group.y $group.width $group.height)
            [void](Add-DrawioVertex $document $root ('group-'+$group.id+'-title') $group.title ($textStyle+"align=left;fontSize=$(Number (24*$scale));fontStyle=1;fontColor=$($group.accent);") ($group.x+20*$scale) ($group.y+16*$scale) ($group.width-40*$scale) (42*$scale))
            [void](Add-DrawioVertex $document $root ('group-'+$group.id+'-subtitle') $group.subtitle ($textStyle+"align=left;fontSize=$(Number (17*$scale));fontColor=$($theme.subtitleColor);") ($group.x+20*$scale) ($group.y+55*$scale) ($group.width-40*$scale) (30*$scale))
        }
        $nodeMap=@{}
        foreach ($node in $page.nodes) { $nodeMap[$node.id]=$node }
        foreach ($edge in $page.edges) {
            $source=$nodeMap[$edge.source]
            $target=$nodeMap[$edge.target]
            $first=$edge.points[0]
            $last=$edge.points[$edge.points.Count-1]
            $startArrow=Get-DrawioCardinality (Get-Field $edge 'startCardinality' '')
            $endArrow=Get-DrawioCardinality (Get-Field $edge 'endCardinality' '')
            $style="edgeStyle=none;rounded=0;html=0;strokeWidth=$(Number (2.1*$scale));startArrow=$startArrow;endArrow=$endArrow;startSize=$(Number (22*$scale));endSize=$(Number (22*$scale));startFill=0;endFill=0;strokeColor=$($edge.color);"
            if (Get-Field $edge 'dashed' $false) { $style+='dashed=1;dashPattern=7 5;' }
            $style+='exitX='+(Number (($first.x-$source.x)/$source.width))+';exitY='+(Number (($first.y-$source.y)/$source.height))+';exitPerimeter=0;'
            $style+='entryX='+(Number (($last.x-$target.x)/$target.width))+';entryY='+(Number (($last.y-$target.y)/$target.height))+';entryPerimeter=0;'
            $cell=Add-XmlElement $document $root 'mxCell' @{
                id=$edge.id;style=$style;edge='1';parent='1';source=$edge.source;target=$edge.target
            }
            $geometry=Add-XmlElement $document $cell 'mxGeometry' @{relative='1';as='geometry'}
            $points=Add-XmlElement $document $geometry 'Array' @{as='points'}
            for ($index=1;$index -lt $edge.points.Count-1;$index++) {
                [void](Add-XmlElement $document $points 'mxPoint' @{x=(Number $edge.points[$index].x);y=(Number $edge.points[$index].y)})
            }
        }
        foreach ($node in $page.nodes) {
            $radius=[double](Get-Field $node 'radius' (12*$scale))
            $rounded=0
            if ($radius -gt 0) { $rounded=1 }
            $fill=Get-Field $node 'fill' $theme.nodeFill
            $nodeText=Get-Field $node 'textColor' $theme.nodeText
            $bodyText=Get-Field $node 'bodyColor' $theme.bodyText
            $style="rounded=$rounded;arcSize=$(Number $radius);html=0;whiteSpace=wrap;fillColor=$fill;strokeColor=$($node.accent);strokeWidth=$(Number (1.8*$scale));"
            if (Get-Field $node 'reference' $false) { $style+='dashed=1;dashPattern=6 4;' }
            [void](Add-DrawioVertex $document $root $node.id '' $style $node.x $node.y $node.width $node.height)
            $layout=Get-NodeTextLayout $node $scale
            [void](Add-DrawioVertex $document $root ($node.id+'-title') $node.title ($textStyle+"align=center;verticalAlign=middle;fontSize=$(Number $layout.titleSize);fontStyle=1;fontColor=$nodeText;") $layout.padding $layout.titleY $layout.width $layout.titleHeight $node.id)
            $lineY=$layout.bodyY
            $bodyIndex=0
            foreach ($line in $node.body) {
                $lines=Wrap-Text $line $layout.width $layout.bodySize
                $height=[Math]::Max(23*$scale,$lines.Count*$layout.bodySize*1.3)
                [void](Add-DrawioVertex $document $root ($node.id+'-body-'+$bodyIndex) $line ($textStyle+"align=center;fontSize=$(Number $layout.bodySize);fontColor=$bodyText;") $layout.padding $lineY $layout.width $height $node.id)
                $lineY+=$height
                $bodyIndex++
            }
        }
        foreach ($edge in $page.edges) {
            $labelSize=[double](Get-Field $edge 'fontSize' (16*$scale))
            [void](Add-DrawioVertex $document $root ($edge.id+'-label') $edge.label ($textStyle+"align=center;verticalAlign=middle;fillColor=$($theme.labelFill);rounded=1;fontSize=$(Number $labelSize);fontColor=$($theme.labelText);spacing=$(Number (3*$scale));") $edge.labelX $edge.labelY $edge.labelWidth $edge.labelHeight)
        }
        [void](Add-DrawioVertex $document $root 'notes-background' '' "rounded=1;arcSize=10;html=0;fillColor=$($theme.noteFill);strokeColor=$($theme.noteStroke);strokeWidth=1;" $page.notesX $page.notesY $page.notesWidth $page.notesHeight)
        $noteY=$page.notesY+12
        $index=0
        foreach ($line in $page.notes) {
            $lines=Wrap-Text $line ($page.notesWidth-40) 18
            $height=[Math]::Max(28,$lines.Count*23.4)
            [void](Add-DrawioVertex $document $root ('note-'+$index) $line ($textStyle+"align=left;fontSize=18;fontColor=$($theme.noteText);") ($page.notesX+20) $noteY ($page.notesWidth-40) $height)
            $noteY+=$height+4
            $index++
        }
    }
    $writerOptions=New-Object System.Xml.XmlWriterSettings
    $writerOptions.Indent=$true
    $writerOptions.Encoding=$utf8
    $writer=[System.Xml.XmlWriter]::Create($Path,$writerOptions)
    try { $document.Save($writer) } finally { $writer.Dispose() }
}

function New-PageScene($Page) {
    $scene=New-Object 'System.Collections.Generic.List[object]'
    $scale=Get-PageScale $Page
    $theme=Get-PageTheme $Page
    Add-Shape $scene 'rect' 0 0 $Page.width $Page.height $theme.background 'none' 0
    Add-Text $scene $Page.title 70 26 ($Page.width-140) 64 36 $theme.titleColor $true 'left'
    Add-Text $scene $Page.subtitle 72 92 ($Page.width-144) 36 20 $theme.subtitleColor $false 'left'
    foreach ($group in $Page.groups) {
        Add-Shape $scene 'rect' $group.x $group.y $group.width $group.height $group.fill '#DCE5EF' (1.5*$scale) $false (18*$scale)
        Add-Text $scene $group.title ($group.x+20*$scale) ($group.y+16*$scale) ($group.width-40*$scale) (42*$scale) (24*$scale) $group.accent $true 'left'
        Add-Text $scene $group.subtitle ($group.x+20*$scale) ($group.y+55*$scale) ($group.width-40*$scale) (30*$scale) (17*$scale) $theme.subtitleColor $false 'left'
    }
    foreach ($edge in $Page.edges) {
        $scene.Add(@{
            type='line';points=$edge.points;stroke=$edge.color;strokeWidth=(2.1*$scale);
            dashed=[bool](Get-Field $edge 'dashed' $false);edgeType='relationship'
        })
    }
    foreach ($node in $Page.nodes) {
        $radius=[double](Get-Field $node 'radius' (12*$scale))
        $fill=Get-Field $node 'fill' $theme.nodeFill
        $nodeText=Get-Field $node 'textColor' $theme.nodeText
        $bodyText=Get-Field $node 'bodyColor' $theme.bodyText
        Add-Shape $scene 'rect' $node.x $node.y $node.width $node.height $fill $node.accent (1.8*$scale) ([bool](Get-Field $node 'reference' $false)) $radius
        $layout=Get-NodeTextLayout $node $scale
        Add-Text $scene $node.title ($node.x+$layout.padding) ($node.y+$layout.titleY) $layout.width $layout.titleHeight $layout.titleSize $nodeText $true
        $lineY=$node.y+$layout.bodyY
        foreach ($line in $node.body) {
            $lines=Wrap-Text $line $layout.width $layout.bodySize
            $height=[Math]::Max(23*$scale,$lines.Count*$layout.bodySize*1.3)
            Add-Text $scene $line ($node.x+$layout.padding) $lineY $layout.width $height $layout.bodySize $bodyText
            $lineY+=$height
        }
    }
    foreach ($edge in $Page.edges) {
        $labelSize=[double](Get-Field $edge 'fontSize' (16*$scale))
        Add-Shape $scene 'rect' $edge.labelX $edge.labelY $edge.labelWidth $edge.labelHeight $theme.labelFill 'none' 0 $false (6*$scale)
        Add-Text $scene $edge.label ($edge.labelX+3*$scale) $edge.labelY ($edge.labelWidth-6*$scale) $edge.labelHeight $labelSize $theme.labelText
    }
    foreach ($edge in $Page.edges) {
        Add-CardinalityMarker $scene $edge.points[0] $edge.points[1] (Get-Field $edge 'startCardinality' '') $edge.color $scale $theme.background
        Add-CardinalityMarker $scene $edge.points[$edge.points.Count-1] $edge.points[$edge.points.Count-2] (Get-Field $edge 'endCardinality' '') $edge.color $scale $theme.background
    }
    Add-Shape $scene 'rect' $Page.notesX $Page.notesY $Page.notesWidth $Page.notesHeight $theme.noteFill $theme.noteStroke 1 $false 14
    $noteY=$Page.notesY+12
    foreach ($line in $Page.notes) {
        $lines=Wrap-Text $line ($Page.notesWidth-40) 18
        $height=[Math]::Max(28,$lines.Count*23.4)
        Add-Text $scene $line ($Page.notesX+20) $noteY ($Page.notesWidth-40) $height 18 $theme.noteText $false 'left'
        $noteY+=$height+4
    }
    return ,$scene
}

try {
    Assert-Model $model
    foreach ($page in $model.pages) {
        $scene=New-PageScene $page
        $stem=Join-Path $OutputDirectory ($model.fileStem+[string](Get-Field $page 'fileSuffix' ''))
        Write-Svg $page $scene ($stem+'.svg')
        Write-Png $page $scene ($stem+'.png')
        Write-Output ('Generated '+$page.name+': PNG '+($page.width*2)+'x'+($page.height*2)+' and SVG')
    }
    Write-ConceptualDrawio $model (Join-Path $OutputDirectory ($model.fileStem+'.drawio'))
    Write-Output ('Generated editable draw.io with '+$model.pages.Count+' pages.')
} finally {
    foreach ($font in $script:fontCache.Values) { $font.Dispose() }
    $script:measurementGraphics.Dispose()
    $script:measurementBitmap.Dispose()
}
